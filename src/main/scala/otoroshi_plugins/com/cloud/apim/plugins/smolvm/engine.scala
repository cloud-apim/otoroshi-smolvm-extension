package otoroshi_plugins.com.cloud.apim.plugins.smolvm

import akka.pattern.after
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** What the plugin needs from the incoming request, decoupled from the Otoroshi context. */
case class SmolInvocation(
    snowflake: String,
    method: String,
    relativeUri: String,
    path: String,
    query: Map[String, String],
    headers: Map[String, String],
    body: Source[ByteString, _]
)

sealed trait InvokeResult
object InvokeResult {
  case class Streamed(status: Int, headers: Map[String, String], body: Source[ByteString, _]) extends InvokeResult
  case class Buffered(status: Int, headers: Map[String, String], body: ByteString)            extends InvokeResult
  case class Failed(status: Int, message: String)                                             extends InvokeResult
}

/**
 * Owns the durable, process-wide state: host registry, round-robin placement, port
 * allocation. Instantiated once in the plugin's `start(env)`. v1 provisions an
 * ephemeral micro-VM per request (boot -> run -> delete). See SPEC.md §3.
 */
class SmolVmEngine(env: Env) {

  private val logger = Logger("cloud-apim-smolvm")

  private val client      = new SmolVmClient(env)
  private val hostCounter = new AtomicInteger(0)
  private val portCounter = new AtomicInteger(0)

  private val portBase  = 20000
  private val portRange = 20000

  // simple TTL cache for hosts fetched from a URL: url -> (expiresAtMs, hosts)
  private val urlHostsCache = new java.util.concurrent.ConcurrentHashMap[String, (Long, Seq[String])]()

  private def nextPort(): Int = portBase + (Math.floorMod(portCounter.getAndIncrement(), portRange))

  private def sanitizeName(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").stripPrefix("-").take(48)

  // ---- host registry --------------------------------------------------------

  private def parseHosts(json: JsValue): Seq[String] = json match {
    case JsArray(values) => values.flatMap(_.asOpt[String]).map(_.trim).filter(_.nonEmpty)
    case obj: JsObject   => (obj \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty)
    case _               => Seq.empty
  }

  private def fetchHostsFromUrl(url: String, ttl: FiniteDuration)(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val now = System.currentTimeMillis()
    Option(urlHostsCache.get(url)).filter(_._1 > now) match {
      case Some((_, hosts)) => Future.successful(hosts)
      case None             =>
        env.Ws
          .url(url)
          .withRequestTimeout(10.seconds)
          .get()
          .map { r =>
            val hosts = if (r.status >= 200 && r.status < 300) parseHosts(r.json) else Seq.empty
            urlHostsCache.put(url, (now + ttl.toMillis, hosts))
            hosts
          }
          .recover {
            case e =>
              logger.error(s"failed to fetch smolvm hosts from $url: ${e.getMessage}")
              Option(urlHostsCache.get(url)).map(_._2).getOrElse(Seq.empty)
          }
    }
  }

  private def hostsFor(cfg: SmolVmFunctionConfig)(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val staticHosts = cfg.hosts.map(_.trim).filter(_.nonEmpty)
    cfg.hostsUrl.filter(_.nonEmpty) match {
      case None      => Future.successful(staticHosts)
      case Some(url) => fetchHostsFromUrl(url, cfg.hostsRefresh).map(fetched => (staticHosts ++ fetched).distinct)
    }
  }

  /** Hosts ordered starting at the current round-robin index, for failover. */
  private def roundRobinOrder(hosts: Seq[String]): Seq[String] = {
    if (hosts.isEmpty) Seq.empty
    else {
      val start = Math.floorMod(hostCounter.getAndIncrement(), hosts.size)
      (0 until hosts.size).map(i => hosts((start + i) % hosts.size))
    }
  }

  // ---- provisioning ---------------------------------------------------------

  private def provision(host: String, name: String, spec: SmolMachineSpec, timeout: FiniteDuration)(implicit
      ec: ExecutionContext
  ): Future[Either[String, Unit]] =
    client.createMachine(host, spec, timeout).flatMap {
      case Left(err) => Future.successful(Left(err))
      case Right(_)  =>
        // smolvm pulls images lazily; pre-pull best-effort so `start` runs the image
        // workload (critical for service mode). Failure is non-fatal: the image may be
        // cached host-side or be a local archive/rootfs.
        client.pullImage(host, name, spec.image, timeout).flatMap { pullRes =>
          pullRes.left.foreach(err => logger.warn(s"pre-pull of '${spec.image}' on $host failed (continuing): $err"))
          client.start(host, name, timeout)
        }
    }

  private def attemptOnHosts(hosts: Seq[String], name: String, spec: SmolMachineSpec, timeout: FiniteDuration)(implicit
      ec: ExecutionContext
  ): Future[Either[String, String]] = {
    val ordered = roundRobinOrder(hosts).take(math.min(hosts.size, 3)).toList
    def loop(remaining: List[String], lastErr: String): Future[Either[String, String]] = remaining match {
      case Nil      => Future.successful(Left(lastErr))
      case h :: t   =>
        provision(h, name, spec, timeout).flatMap {
          case Right(_)  => Future.successful(Right(h))
          case Left(err) =>
            logger.warn(s"provisioning $name on $h failed: $err")
            deleteQuietly(h, name)
            loop(t, err)
        }
    }
    loop(ordered, "no smolvm host attempted")
  }

  private def deleteQuietly(host: String, name: String)(implicit ec: ExecutionContext): Unit =
    client.delete(host, name, 15.seconds).onComplete {
      case scala.util.Success(Left(err)) => logger.warn(s"could not delete machine $name on $host: $err")
      case scala.util.Failure(e)         => logger.warn(s"could not delete machine $name on $host: ${e.getMessage}")
      case _                             => ()
    }

  // ---- spec building --------------------------------------------------------

  private def parsePort(s: String): Option[SmolPort] =
    s.split(":").map(_.trim) match {
      case Array(h, g) => for { hi <- Try(h.toInt).toOption; gi <- Try(g.toInt).toOption } yield SmolPort(hi, gi)
      case _           => None
    }

  private def parseMount(s: String): Option[SmolMount] =
    s.split(":").map(_.trim).toList match {
      case src :: tgt :: Nil       => Some(SmolMount(src, tgt))
      case src :: tgt :: ro :: Nil => Some(SmolMount(src, tgt, readonly = ro.equalsIgnoreCase("ro")))
      case _                       => None
    }

  /** `servicePortMapping` = Some((hostPort, guestPort)) for service mode. */
  private def buildSpec(cfg: SmolVmFunctionConfig, name: String, servicePortMapping: Option[(Int, Int)]): SmolMachineSpec = {
    val isService  = servicePortMapping.isDefined
    val ports      = servicePortMapping.map { case (h, g) => SmolPort(h, g) }.toSeq ++ cfg.ports.flatMap(parsePort)
    val mounts     = cfg.volumes.flatMap(parseMount)
    val netEnabled = cfg.networkEnabled || isService || cfg.allowCidrs.nonEmpty
    // published ports have no inbound path on the default TSI backend
    val backend    = if (ports.nonEmpty) Some("virtio-net") else None
    SmolMachineSpec(
      name = name,
      image = cfg.image,
      cpus = Some(cfg.cpus),
      memoryMb = Some(cfg.memoryMb),
      storageGb = cfg.storageGb,
      overlayGb = cfg.overlayGb,
      network = netEnabled,
      networkBackend = backend,
      gpu = cfg.gpu,
      allowedCidrs = cfg.allowCidrs,
      mounts = mounts,
      ports = ports
    )
  }

  private def serviceBaseUrl(apiHost: String, port: Int): String = {
    val uri    = java.net.URI.create(apiHost.stripSuffix("/"))
    val scheme = Option(uri.getScheme).getOrElse("http")
    val h      = Option(uri.getHost).getOrElse(apiHost.replaceAll("https?://", "").takeWhile(_ != ':'))
    s"$scheme://$h:$port"
  }

  private def waitReady(url: String, deadlineMs: Long, perTry: FiniteDuration)(implicit ec: ExecutionContext): Future[Boolean] =
    client.probe(url, perTry).flatMap {
      case true  => Future.successful(true)
      case false =>
        if (System.currentTimeMillis() >= deadlineMs) Future.successful(false)
        else after(250.millis, env.otoroshiActorSystem.scheduler)(waitReady(url, deadlineMs, perTry))
    }

  // ---- invocation -----------------------------------------------------------

  private def failed(status: Int, msg: String): Future[InvokeResult] = Future.successful(InvokeResult.Failed(status, msg))

  def invoke(inv: SmolInvocation, cfg: SmolVmFunctionConfig)(implicit ec: ExecutionContext, mat: Materializer): Future[InvokeResult] = {
    if (cfg.image.trim.isEmpty) failed(500, "no image configured for this function")
    else {
      hostsFor(cfg).flatMap { hosts =>
        if (hosts.isEmpty) failed(502, "no smolvm host available (check 'hosts' / 'hosts_url')")
        else {
          val name      = sanitizeName(s"otoroshi-fn-${inv.snowflake}")
          val isService = cfg.mode == "service"
          val hostPort  = if (isService) nextPort() else 0
          val spec      = buildSpec(cfg, name, if (isService) Some((hostPort, cfg.servicePort)) else None)
          attemptOnHosts(hosts, name, spec, cfg.bootTimeout).flatMap {
            case Left(err)   => failed(502, s"could not provision micro-VM: $err")
            case Right(host) =>
              if (isService) runService(host, name, hostPort, cfg, inv)
              else runExec(host, name, cfg, inv)
          }
        }
      }
    }
  }

  private def runService(host: String, name: String, hostPort: Int, cfg: SmolVmFunctionConfig, inv: SmolInvocation)(implicit
      ec: ExecutionContext,
      mat: Materializer
  ): Future[InvokeResult] = {
    val base      = serviceBaseUrl(host, hostPort)
    val readyUrl  = base + cfg.readinessPath
    val deadline  = System.currentTimeMillis() + cfg.readinessTimeout.toMillis
    waitReady(readyUrl, deadline, 1.second).flatMap {
      case false =>
        deleteQuietly(host, name)
        failed(504, s"service did not become ready within ${cfg.readinessTimeout}")
      case true  =>
        val target     = base + inv.relativeUri
        val fwdHeaders = inv.headers.filterNot { case (k, _) => k.equalsIgnoreCase("Host") }
        client
          .proxy(target, inv.method, fwdHeaders, inv.body, cfg.requestTimeout)
          .map { resp =>
            val bodyWithCleanup = resp.body.alsoTo(Sink.onComplete(_ => deleteQuietly(host, name)))
            InvokeResult.Streamed(resp.status, resp.headers, bodyWithCleanup)
          }
          .recover {
            case e =>
              deleteQuietly(host, name)
              InvokeResult.Failed(502, s"proxy error: ${e.getMessage}")
          }
    }
  }

  private def runExec(host: String, name: String, cfg: SmolVmFunctionConfig, inv: SmolInvocation)(implicit
      ec: ExecutionContext,
      mat: Materializer
  ): Future[InvokeResult] = {
    cfg.execCommand.filter(_.nonEmpty) match {
      case None          =>
        deleteQuietly(host, name)
        failed(500, "exec_command is required for 'exec' mode")
      case Some(command) =>
        inv.body
          .runFold(ByteString.empty)(_ ++ _)
          .flatMap { bytes =>
            val b64      = java.util.Base64.getEncoder.encodeToString(bytes.toArray)
            val envelope = ExecEnvelope.requestJson(inv.method, inv.path, inv.query, inv.headers, b64)
            val execReq  = ExecRequest(
              command = command,
              stdin = Some(Json.stringify(envelope)),
              env = cfg.env.toSeq,
              workdir = cfg.workdir,
              timeoutSecs = Some(cfg.requestTimeout.toSeconds)
            )
            client.exec(host, name, execReq, cfg.requestTimeout).map {
              case Left(err)                              => InvokeResult.Failed(502, err)
              case Right(resp) if !resp.success           =>
                InvokeResult.Failed(502, s"function exited with ${resp.exitCode}: ${resp.stderr.take(512)}")
              case Right(resp)                            =>
                ExecEnvelope.parseResponse(resp.stdout) match {
                  case Left(err) => InvokeResult.Failed(502, s"$err; stderr=${resp.stderr.take(256)}")
                  case Right(fr) => InvokeResult.Buffered(fr.status, fr.headers, ByteString(fr.body))
                }
            }
          }
          .andThen { case _ => deleteQuietly(host, name) }
          .recover { case e => InvokeResult.Failed(502, s"exec error: ${e.getMessage}") }
    }
  }
}

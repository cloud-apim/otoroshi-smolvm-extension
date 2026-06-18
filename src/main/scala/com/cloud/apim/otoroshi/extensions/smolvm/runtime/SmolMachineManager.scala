package com.cloud.apim.otoroshi.extensions.smolvm.runtime

import akka.pattern.after
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.smolvm.client._
import com.cloud.apim.otoroshi.extensions.smolvm.entities.{ExecEnvelope, ExecRequest, SmolMachine, SmolMachineSpec, SmolMachineSpecV1, SmolPort}
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** One live instance of a SmolMachine, recorded in the external placement state. */
case class InstanceRecord(
    slot: Int,
    name: String,
    host: String,
    hostPort: Int,
    status: String, // only "ready" instances are ever stored / routed to
    serverLaunched: Boolean,
    createdAtMs: Long,
    lastUsedAtMs: Long
) {
  def json: JsValue = Json.obj(
    "slot" -> slot, "name" -> name, "host" -> host, "host_port" -> hostPort,
    "status" -> status, "server_launched" -> serverLaunched,
    "created_at_ms" -> createdAtMs, "last_used_at_ms" -> lastUsedAtMs
  )
}
object InstanceRecord {
  def parse(s: String): Option[InstanceRecord] = Try {
    val j = Json.parse(s)
    InstanceRecord(
      slot = (j \ "slot").asOpt[Int].getOrElse(0),
      name = (j \ "name").as[String],
      host = (j \ "host").as[String],
      hostPort = (j \ "host_port").asOpt[Int].getOrElse(0),
      status = (j \ "status").asOpt[String].getOrElse("ready"),
      serverLaunched = (j \ "server_launched").asOpt[Boolean].getOrElse(false),
      createdAtMs = (j \ "created_at_ms").asOpt[Long].getOrElse(0L),
      lastUsedAtMs = (j \ "last_used_at_ms").asOpt[Long].getOrElse(0L)
    )
  }.toOption
}

/**
 * Owns the lazy, cluster-safe instance pool of a SmolMachine and the per-request routing.
 *
 *  - Placement (instance -> host) lives in [[SmolStateBackend]] (external Redis), keyed by machine id.
 *  - Cold start (no ready instance) blocks to provision the first one, guarded by a Redis lock.
 *  - Subsequent requests are served round-robin from ready instances; the pool grows in the
 *    background up to `spec.instances`.
 *  - HTTP calls to smolvm reuse the v1 [[SmolVmClient]].
 */
class SmolMachineManager(env: Env, state: SmolStateBackend) {

  private val logger = Logger("cloud-apim-smolmachine")
  private val client = new SmolVmClient(env)

  private val portBase  = 20000
  private val portRange = 20000
  private val lockTtlMs  = 60000L
  // service-via-exec keeps the launching exec connection open for the server's lifetime (until the VM is
  // deleted/reaped). play-ws caps a request timeout at Int.MaxValue ms (~24.8 days), so use just under that.
  private val serverExecTimeout = 2000000000.millis

  // local counters for the ephemeral path (instances = 0): no external state needed
  private val ephemeralHostCounter = new AtomicInteger(0)
  private val ephemeralPortCounter = new AtomicInteger(0)

  // small TTL cache for hosts fetched from a url: url -> (expiresAtMs, hosts)
  private val urlHostsCache = new ConcurrentHashMap[String, (Long, Seq[String])]()

  private def instancesKey(id: String): String = s"smolvm:state:$id:instances"
  private def lockKey(id: String): String      = s"smolvm:state:$id:lock"
  private def rrKey(id: String): String        = s"smolvm:state:$id:rr"
  private def portKey: String                  = "smolvm:state:_ports"

  private def floorMod(a: Long, b: Int): Int = Math.floorMod(a, b.toLong).toInt

  private def sanitize(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").stripPrefix("-").take(32)

  // smolvm rejects names with consecutive hyphens / leading-trailing hyphens, so collapse after joining
  // (sanitize(machineId) may be truncated and end with a hyphen, which would create "--" before the slot)
  private def instanceName(machineId: String, slot: Int): String =
    s"otoroshi-smol-${sanitize(machineId)}-$slot".replaceAll("-+", "-").stripPrefix("-").stripSuffix("-")

  private def isProxyMode(spec: SmolMachineSpec): Boolean = spec.mode == "service" || spec.mode == "service-via-exec"
  // JS runtimes (node / bun): expose the RPC facade, inline code and dependency install
  private def isJsRuntime(spec: SmolMachineSpec): Boolean   = JsRuntimeCommands.forRuntime(spec.runtime).isDefined
  private def jsBin(spec: SmolMachineSpec): String          = JsRuntimeCommands.forRuntime(spec.runtime).map(_.bin).getOrElse("node")
  private def hasInlineCode(spec: SmolMachineSpec): Boolean = isJsRuntime(spec) && spec.code.exists(_.trim.nonEmpty)
  // RPC only when there is no inline code (inline code overrides the command instead)
  private def isJsRpc(spec: SmolMachineSpec): Boolean       = isJsRuntime(spec) && spec.mode == "exec" && !hasInlineCode(spec)

  private def nodeCodePath(spec: SmolMachineSpec): String = if (spec.codeFile.startsWith("/")) spec.codeFile else s"/${spec.codeFile}"
  private def parentDir(path: String): String = { val i = path.lastIndexOf('/'); if (i <= 0) "/" else path.substring(0, i) }

  /** Inline code overrides exec_command / launch_command with `<bin> <codeFile>` (node / bun). */
  private def effectiveCommand(spec: SmolMachineSpec, configured: Option[Seq[String]]): Option[Seq[String]] =
    if (hasInlineCode(spec)) Some(Seq(jsBin(spec), nodeCodePath(spec))) else configured

  // ---- host resolution ------------------------------------------------------

  private def parseHosts(json: JsValue): Seq[String] = json match {
    case JsArray(values) => values.flatMap(_.asOpt[String]).map(_.trim).filter(_.nonEmpty)
    case obj: JsObject   => (obj \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty)
    case _               => Seq.empty
  }

  private def hostsFor(spec: SmolMachineSpec)(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val staticHosts = spec.hosts.map(_.trim).filter(_.nonEmpty)
    spec.hostsUrl.filter(_.nonEmpty) match {
      case None      => Future.successful(staticHosts)
      case Some(url) =>
        val now = System.currentTimeMillis()
        Option(urlHostsCache.get(url)).filter(_._1 > now) match {
          case Some((_, hosts)) => Future.successful((staticHosts ++ hosts).distinct)
          case None             =>
            env.Ws.url(url).withRequestTimeout(10.seconds).get().map { r =>
              val hosts = if (r.status >= 200 && r.status < 300) parseHosts(r.json) else Seq.empty
              urlHostsCache.put(url, (now + spec.hostsRefresh.toMillis, hosts))
              (staticHosts ++ hosts).distinct
            }.recover { case e =>
              logger.error(s"failed to fetch smolvm hosts from $url: ${e.getMessage}")
              (staticHosts ++ Option(urlHostsCache.get(url)).map(_._2).getOrElse(Seq.empty)).distinct
            }
        }
    }
  }

  private def serviceBaseUrl(apiHost: String, port: Int): String = {
    val uri    = java.net.URI.create(apiHost.stripSuffix("/"))
    val scheme = Option(uri.getScheme).getOrElse("http")
    val h      = Option(uri.getHost).getOrElse(apiHost.replaceAll("https?://", "").takeWhile(_ != ':'))
    s"$scheme://$h:$port"
  }

  // ---- registry helpers -----------------------------------------------------

  private def readyInstances(machineId: String)(implicit ec: ExecutionContext): Future[Seq[InstanceRecord]] =
    state.hgetAll(instancesKey(machineId)).map { m =>
      m.values.flatMap(InstanceRecord.parse).filter(_.status == "ready").toSeq.sortBy(_.slot)
    }

  private def usedSlots(machineId: String)(implicit ec: ExecutionContext): Future[Set[Int]] =
    state.hgetAll(instancesKey(machineId)).map(_.keySet.flatMap(s => Try(s.toInt).toOption))

  private def storeInstance(machineId: String, rec: InstanceRecord)(implicit ec: ExecutionContext): Future[Unit] =
    state.hset(instancesKey(machineId), rec.slot.toString, Json.stringify(rec.json))

  private def touch(machineId: String, rec: InstanceRecord)(implicit ec: ExecutionContext): Future[Unit] =
    storeInstance(machineId, rec.copy(lastUsedAtMs = System.currentTimeMillis()))

  private def nextPort()(implicit ec: ExecutionContext): Future[Int] =
    state.incr(portKey).map(n => portBase + floorMod(n, portRange))

  // ---- public api -----------------------------------------------------------

  def invoke(machine: SmolMachine, inv: SmolInvocation)(implicit ec: ExecutionContext): Future[InvokeResult] = {
    val spec = machine.spec
    if (!machine.enabled) Future.successful(InvokeResult.Failed(503, s"smol machine '${machine.id}' is disabled"))
    else if (spec.image.trim.isEmpty && spec.from.isEmpty) Future.successful(InvokeResult.Failed(500, "no image configured for this smol machine"))
    else {
      hostsFor(spec).flatMap { hosts =>
        if (hosts.isEmpty) Future.successful(InvokeResult.Failed(502, "no smolvm host available (check spec.hosts / spec.hosts_url)"))
        else if (spec.instances <= 0) invokeEphemeral(machine, hosts, inv)
        else resolveInstance(machine, hosts).flatMap {
          case Left(err)  => Future.successful(InvokeResult.Failed(502, s"could not get a smolvm instance: $err"))
          case Right(rec) => route(machine, rec, inv)
        }
      }
    }
  }

  /** Dispatch a request to a (ready) instance based on mode/runtime. */
  private def route(machine: SmolMachine, rec: InstanceRecord, inv: SmolInvocation)(implicit ec: ExecutionContext): Future[InvokeResult] = {
    val spec = machine.spec
    if (isJsRpc(spec)) NodeRuntime.handle(client, rec.host, rec.name, spec, inv)
    else if (isProxyMode(spec)) runProxy(rec, spec, inv)
    else runExec(rec, spec, inv)
  }

  // ---- ephemeral path (instances = 0): one fresh micro-VM per request, torn down after ----

  private def ephemeralName(machineId: String, snowflake: String): String =
    s"otoroshi-smol-${sanitize(machineId)}-${sanitize(snowflake)}".replaceAll("-+", "-").stripPrefix("-").stripSuffix("-").take(60)

  private def invokeEphemeral(machine: SmolMachine, hosts: Seq[String], inv: SmolInvocation)(implicit ec: ExecutionContext): Future[InvokeResult] = {
    val spec        = machine.spec
    val name        = ephemeralName(machine.id, inv.snowflake)
    val host        = hosts(floorMod(ephemeralHostCounter.getAndIncrement().toLong, hosts.size))
    val hostPortOpt = if (isProxyMode(spec)) Some(portBase + floorMod(ephemeralPortCounter.getAndIncrement().toLong, portRange)) else None
    logger.info(s"[$name] ephemeral invoke on $host mode=${spec.mode} runtime=${spec.runtime}")
    createAndBringUp(machine, host, name, -1, hostPortOpt).flatMap {
      case Left(err)  => Future.successful(InvokeResult.Failed(502, s"could not provision ephemeral micro-VM: $err"))
      case Right(rec) => route(machine, rec, inv).andThen { case _ => deleteQuietly(host, name) }
    }
  }

  private def deleteQuietly(host: String, name: String)(implicit ec: ExecutionContext): Unit =
    client.delete(host, name, 15.seconds).onComplete {
      case Success(Right(_))  => logger.info(s"[$name] ephemeral machine deleted (teardown done)")
      case Success(Left(err)) => logger.warn(s"[$name] could not delete ephemeral machine on $host: $err")
      case Failure(e)         => logger.warn(s"[$name] could not delete ephemeral machine on $host: ${e.getMessage}")
    }

  /** Serve from a ready instance if any (and grow in background), else cold-start one. */
  private def resolveInstance(machine: SmolMachine, hosts: Seq[String])(implicit ec: ExecutionContext): Future[Either[String, InstanceRecord]] = {
    val spec = machine.spec
    readyInstances(machine.id).flatMap { ready =>
      if (ready.nonEmpty) {
        state.incr(rrKey(machine.id)).flatMap { n =>
          val chosen = ready(floorMod(n - 1, ready.size))
          if (ready.size < spec.instances) growInBackground(machine, hosts)
          touch(machine.id, chosen).map(_ => Right(chosen))
        }
      } else {
        coldStart(machine, hosts, System.currentTimeMillis() + spec.bootTimeout.toMillis)
      }
    }
  }

  private def coldStart(machine: SmolMachine, hosts: Seq[String], deadlineMs: Long)(implicit ec: ExecutionContext): Future[Either[String, InstanceRecord]] = {
    state.acquireLock(lockKey(machine.id), lockTtlMs).flatMap {
      case true =>
        provisionFreeSlot(machine, hosts).andThen { case _ => state.releaseLock(lockKey(machine.id)) }
      case false =>
        // another node is provisioning; wait then re-check
        after(300.millis, env.otoroshiActorSystem.scheduler) {
          readyInstances(machine.id).flatMap { ready =>
            if (ready.nonEmpty) Future.successful(Right(ready.head))
            else if (System.currentTimeMillis() >= deadlineMs) Future.successful(Left("timed out waiting for an instance to become ready"))
            else coldStart(machine, hosts, deadlineMs)
          }
        }
    }
  }

  private def growInBackground(machine: SmolMachine, hosts: Seq[String])(implicit ec: ExecutionContext): Unit = {
    state.acquireLock(lockKey(machine.id), lockTtlMs).flatMap {
      case false => Future.successful(())
      case true  =>
        provisionFreeSlot(machine, hosts)
          .andThen { case _ => state.releaseLock(lockKey(machine.id)) }
          .map(_ => ())
    }.recover { case e => logger.warn(s"[${machine.id}] background pool growth failed: ${e.getMessage}") }
  }

  private def provisionFreeSlot(machine: SmolMachine, hosts: Seq[String])(implicit ec: ExecutionContext): Future[Either[String, InstanceRecord]] = {
    val spec = machine.spec
    usedSlots(machine.id).flatMap { used =>
      (0 until spec.instances).find(s => !used.contains(s)) match {
        case None       => readyInstances(machine.id).map(r => r.headOption.toRight("pool is full and no slot available"))
        case Some(slot) => provisionSlot(machine, hosts, slot)
      }
    }
  }

  private def buildCreateBody(spec: SmolMachineSpec, name: String, hostPort: Option[Int]): SmolMachineSpecV1 = {
    val servicePorts = hostPort.map(hp => SmolPort(hp, spec.servicePort)).toSeq
    val ports        = servicePorts ++ spec.ports
    val netEnabled   = spec.network || hostPort.isDefined || spec.allowCidrs.nonEmpty
    val backend      = if (ports.nonEmpty) Some("virtio-net") else None
    SmolMachineSpecV1(
      name = name,
      image = spec.image,
      from = spec.from,
      cpus = Some(spec.cpus),
      memoryMb = Some(spec.memoryMb),
      storageGb = spec.storageGb,
      overlayGb = spec.overlayGb,
      network = netEnabled,
      networkBackend = backend,
      gpu = spec.gpu,
      allowedCidrs = spec.allowCidrs,
      mounts = spec.mounts,
      ports = ports
    )
  }

  /** Pooled provisioning: pick host/port, bring the VM up, then record it in the registry. */
  private def provisionSlot(machine: SmolMachine, hosts: Seq[String], slot: Int)(implicit ec: ExecutionContext): Future[Either[String, InstanceRecord]] = {
    val spec = machine.spec
    val name = instanceName(machine.id, slot)
    state.incr(rrKey(machine.id)).flatMap { rr =>
      val host = hosts(floorMod(rr, hosts.size))
      val portF: Future[Option[Int]] = if (isProxyMode(spec)) nextPort().map(Some(_)) else Future.successful(None)
      portF.flatMap { hostPortOpt =>
        createAndBringUp(machine, host, name, slot, hostPortOpt).flatMap {
          case Right(rec) => storeInstance(machine.id, rec).map(_ => Right(rec))
          case left       => Future.successful(left)
        }
      }
    }
  }

  /** create -> start -> bring up (launch + readiness). Does NOT touch the registry; returns the record. */
  private def createAndBringUp(machine: SmolMachine, host: String, name: String, slot: Int, hostPortOpt: Option[Int])(implicit ec: ExecutionContext): Future[Either[String, InstanceRecord]] = {
    val spec = machine.spec
    val body = buildCreateBody(spec, name, hostPortOpt)
    logger.info(s"[$name] provisioning on $host mode=${spec.mode} runtime=${spec.runtime}${hostPortOpt.fold("")(p => s" port=$p->${spec.servicePort}")}")
    client.createMachine(host, body, spec.bootTimeout).flatMap {
      case Left(err) =>
        logger.warn(s"[$name] create failed on $host: $err")
        client.delete(host, name, 15.seconds)
        Future.successful(Left(err))
      case Right(_)  =>
        client.start(host, name, spec.bootTimeout).flatMap {
          case Left(err) =>
            logger.warn(s"[$name] start failed on $host: $err")
            client.delete(host, name, 15.seconds)
            Future.successful(Left(err))
          case Right(_)  =>
            bringUp(machine, host, name, slot, hostPortOpt)
        }
    }.recover { case e =>
      logger.error(s"[$name] provisioning error on $host: ${e.getMessage}")
      client.delete(host, name, 15.seconds)
      Left(e.getMessage)
    }
  }

  /**
   * node inline code (runtime=node + spec.code): install dependencies (once) and write the code into
   * the VM before it is used. No-op for non-node machines or when neither code nor deps are set.
   */
  private def prepareNode(machine: SmolMachine, host: String, name: String)(implicit ec: ExecutionContext): Future[Either[String, Unit]] = {
    val spec = machine.spec
    if (!isJsRuntime(spec) || (spec.code.forall(_.trim.isEmpty) && spec.dependencies.isEmpty)) Future.successful(Right(()))
    else {
      val rt       = JsRuntimeCommands.forRuntime(spec.runtime).getOrElse(JsRuntimeCommands.node)
      val codePath = nodeCodePath(spec)
      val dir      = parentDir(codePath)
      def execOk(label: String, req: ExecRequest, timeout: FiniteDuration): Future[Either[String, Unit]] =
        client.exec(host, name, req, timeout).map {
          case Left(err)                    => Left(s"$label failed: $err")
          case Right(r) if !r.success       => Left(s"$label exited ${r.exitCode}: ${r.stderr.take(300)}")
          case Right(_)                     => Right(())
        }
      execOk("mkdir", ExecRequest(Seq("sh", "-c", s"mkdir -p $dir"), None, Seq.empty, None, Some(15L)), 30.seconds).flatMap {
        case Left(e)  => Future.successful(Left(e))
        case Right(_) =>
          val depsF =
            if (spec.dependencies.nonEmpty) {
              val installCmd = rt.install ++ spec.dependencies
              logger.info(s"[$name] ${installCmd.mkString(" ")} (in $dir)")
              execOk("install dependencies", ExecRequest(installCmd, None, spec.env.toSeq, Some(dir), Some(spec.bootTimeout.toSeconds)), spec.bootTimeout)
            } else Future.successful(Right(()))
          depsF.flatMap {
            case Left(e)  => Future.successful(Left(e))
            case Right(_) =>
              spec.code.filter(_.trim.nonEmpty) match {
                case None       => Future.successful(Right(()))
                case Some(code) =>
                  logger.info(s"[$name] writing inline node code to $codePath (${code.length} chars)")
                  client.putFile(host, name, codePath, ByteString(code.getBytes(StandardCharsets.UTF_8)), spec.requestTimeout)
              }
          }
      }
    }
  }

  /** prepare node code/deps, then (service-via-exec) launch the server, then wait for readiness on proxy modes. */
  private def bringUp(machine: SmolMachine, host: String, name: String, slot: Int, hostPortOpt: Option[Int])(implicit ec: ExecutionContext): Future[Either[String, InstanceRecord]] = {
    val spec = machine.spec

    def record(serverLaunched: Boolean): InstanceRecord = {
      val now = System.currentTimeMillis()
      InstanceRecord(slot, name, host, hostPortOpt.getOrElse(0), "ready", serverLaunched, now, now)
    }

    def launchAndReady(): Future[Either[String, InstanceRecord]] = {
    val launchF: Future[Either[String, Boolean]] = (spec.mode, effectiveCommand(spec, spec.launchCommand)) match {
      case ("service-via-exec", Some(cmd)) if cmd.nonEmpty =>
        // Run the server in the FOREGROUND of an exec connection we keep open (fire-and-forget):
        // a backgrounded process (`nohup`/`setsid` + `&`) gets reaped when smolvm closes the exec.
        // The server lives as long as this connection is open; it dies when the VM is deleted/reaped.
        // `timeoutSecs = None` => no guest-side time limit; a very long HTTP timeout keeps it open.
        val sh = s"exec ${cmd.mkString(" ")} > /tmp/smolvm-server.log 2>&1"
        logger.info(s"[$name] launching server (foreground, kept-open exec): ${cmd.mkString(" ")}")
        client.exec(host, name, ExecRequest(Seq("sh", "-c", sh), None, spec.env.toSeq, spec.workdir, None), serverExecTimeout)
          .onComplete(res => logger.info(s"[$name] server exec connection ended: $res"))
        Future.successful(Right(true))
      case ("service-via-exec", _)                          =>
        Future.successful(Left("mode 'service-via-exec' requires a non-empty launch_command (or spec.code)"))
      case _                                                =>
        Future.successful(Right(false))
    }

    launchF.flatMap {
      case Left(err)             =>
        client.delete(host, name, 15.seconds)
        Future.successful(Left(err))
      case Right(serverLaunched) =>
        if (isProxyMode(spec)) {
          val base     = serviceBaseUrl(host, hostPortOpt.get)
          val readyUrl = base + spec.readinessPath
          val deadline = System.currentTimeMillis() + spec.readinessTimeout.toMillis
          logger.info(s"[$name] waiting readiness at $readyUrl (timeout ${spec.readinessTimeout})")
          waitReady(readyUrl, deadline).flatMap {
            case false =>
              // pull the server log to explain WHY it never came up (crash, wrong port, missing dep, ...)
              serverLogTail(host, name).map { log =>
                val hint = if (log.trim.nonEmpty) s" — server log:\n$log" else " — server log empty (process likely never started / was killed; use a keep-alive image and check the launch command)"
                logger.warn(s"[$name] not ready after ${spec.readinessTimeout}$hint — tearing down")
                client.delete(host, name, 15.seconds)
                Left(s"instance did not become ready within ${spec.readinessTimeout}${if (log.trim.nonEmpty) s"; server log: ${log.take(500)}" else ""}")
              }
            case true  =>
              Future.successful(Right(record(serverLaunched)))
          }
        } else {
          Future.successful(Right(record(serverLaunched)))
        }
    }
    }

    prepareNode(machine, host, name).flatMap {
      case Left(err) =>
        client.delete(host, name, 15.seconds)
        Future.successful(Left(err))
      case Right(_)  => launchAndReady()
    }
  }

  /** best-effort tail of the service-via-exec server log, for diagnostics on readiness failure */
  private def serverLogTail(host: String, name: String)(implicit ec: ExecutionContext): Future[String] =
    client.exec(host, name, ExecRequest(Seq("sh", "-c", "tail -n 30 /tmp/smolvm-server.log 2>/dev/null || true"), None, Seq.empty, None, Some(10L)), 15.seconds)
      .map { case Right(r) => r.stdout.take(1500); case _ => "" }
      .recover { case _ => "" }

  private def waitReady(url: String, deadlineMs: Long)(implicit ec: ExecutionContext): Future[Boolean] =
    client.probe(url, 1.second).flatMap {
      case true  => Future.successful(true)
      case false =>
        if (System.currentTimeMillis() >= deadlineMs) Future.successful(false)
        else after(250.millis, env.otoroshiActorSystem.scheduler)(waitReady(url, deadlineMs))
    }

  // ---- routing --------------------------------------------------------------

  private def runProxy(rec: InstanceRecord, spec: SmolMachineSpec, inv: SmolInvocation)(implicit ec: ExecutionContext): Future[InvokeResult] = {
    val target     = serviceBaseUrl(rec.host, rec.hostPort) + inv.relativeUri
    val fwdHeaders = inv.headers.filterNot { case (k, _) =>
      k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Content-Length") || k.equalsIgnoreCase("Transfer-Encoding")
    }
    logger.info(s"[${rec.name}] proxy ${inv.method} -> $target (${inv.bodyBytes.length}b)")
    client.proxy(target, inv.method, fwdHeaders, inv.bodyBytes, spec.requestTimeout).map { resp =>
      InvokeResult.Buffered(resp.status, resp.headers, resp.body)
    }.recover { case e =>
      logger.error(s"[${rec.name}] proxy error to $target: ${e.getMessage}")
      InvokeResult.Failed(502, s"proxy error: ${e.getMessage}")
    }
  }

  private def runExec(rec: InstanceRecord, spec: SmolMachineSpec, inv: SmolInvocation)(implicit ec: ExecutionContext): Future[InvokeResult] = {
    effectiveCommand(spec, spec.execCommand).filter(_.nonEmpty) match {
      case None          => Future.successful(InvokeResult.Failed(500, "exec_command (or spec.code) is required for 'exec' mode"))
      case Some(command) =>
        val b64      = java.util.Base64.getEncoder.encodeToString(inv.bodyBytes.toArray)
        val envelope = ExecEnvelope.requestJson(inv.method, inv.path, inv.query, inv.headers, b64)
        val req      = ExecRequest(command, Some(Json.stringify(envelope)), spec.env.toSeq, spec.workdir, Some(spec.requestTimeout.toSeconds))
        logger.info(s"[${rec.name}] exec ${command.mkString(" ")} (stdin ${inv.bodyBytes.size}b)")
        client.exec(rec.host, rec.name, req, spec.requestTimeout).map {
          case Left(err)                    => InvokeResult.Failed(502, err)
          case Right(resp) if !resp.success => InvokeResult.Failed(502, s"function exited with ${resp.exitCode}: ${resp.stderr.take(512)}")
          case Right(resp)                  =>
            ExecEnvelope.parseResponse(resp.stdout) match {
              case Left(err) => InvokeResult.Failed(502, s"$err; stderr=${resp.stderr.take(256)}")
              case Right(fr) => InvokeResult.Buffered(fr.status, fr.headers, ByteString(fr.body))
            }
        }.recover { case e => InvokeResult.Failed(502, s"exec error: ${e.getMessage}") }
    }
  }

  // ---- reaper (idle / orphan GC) -------------------------------------------

  /** Delete instances idle for longer than `idle_timeout` and prune them from the registry. */
  def reap(machine: SmolMachine)(implicit ec: ExecutionContext): Future[Int] = {
    val spec = machine.spec
    state.hgetAll(instancesKey(machine.id)).flatMap { m =>
      val now      = System.currentTimeMillis()
      val toReap   = m.flatMap { case (field, v) => InstanceRecord.parse(v).map(field -> _) }
        .filter { case (_, rec) => now - rec.lastUsedAtMs > spec.idleTimeout.toMillis }
        .toSeq
      Future.sequence(toReap.map { case (field, rec) =>
        logger.info(s"[${rec.name}] reaping idle instance on ${rec.host} (idle ${(now - rec.lastUsedAtMs) / 1000}s)")
        client.delete(rec.host, rec.name, 15.seconds)
          .recover { case e => Left(e.getMessage) }
          .flatMap(_ => state.hdel(instancesKey(machine.id), field))
      }).map(_ => toReap.size)
    }.recover { case e => logger.warn(s"[${machine.id}] reap failed: ${e.getMessage}"); 0 }
  }
}

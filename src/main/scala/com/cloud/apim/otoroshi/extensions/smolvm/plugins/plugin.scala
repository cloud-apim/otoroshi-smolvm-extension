package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.smolvm.client.{InvokeResult, SmolInvocation, SmolVmEngine}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// -----------------------------------------------------------------------------
// Configuration
// -----------------------------------------------------------------------------

case class SmolVmFunctionConfig(
    mode: String = "service",                       // "service" | "exec"
    hosts: Seq[String] = Seq.empty,
    hostsUrl: Option[String] = None,
    hostsRefresh: FiniteDuration = 60.seconds,
    image: String = "",
    networkEnabled: Boolean = false,
    allowCidrs: Seq[String] = Seq.empty,
    cpus: Int = 2,
    memoryMb: Int = 512,
    storageGb: Option[Int] = None,
    overlayGb: Option[Int] = None,
    gpu: Boolean = false,
    env: Map[String, String] = Map.empty,
    volumes: Seq[String] = Seq.empty,
    ports: Seq[String] = Seq.empty,
    workdir: Option[String] = None,
    servicePort: Int = 8080,
    readinessPath: String = "/",
    readinessTimeout: FiniteDuration = 10.seconds,
    execCommand: Option[Seq[String]] = None,
    requestTimeout: FiniteDuration = 30.seconds,
    bootTimeout: FiniteDuration = 60.seconds,
    isolation: String = "ephemeral"                 // "ephemeral"
) extends NgPluginConfig {
  def json: JsValue = SmolVmFunctionConfig.format.writes(this)
}

object SmolVmFunctionConfig {

  val default = SmolVmFunctionConfig()

  val format: Format[SmolVmFunctionConfig] = new Format[SmolVmFunctionConfig] {

    override def writes(o: SmolVmFunctionConfig): JsValue = Json.obj(
      "mode"              -> o.mode,
      "hosts"             -> o.hosts,
      "hosts_url"         -> o.hostsUrl.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "hosts_refresh"     -> o.hostsRefresh.toMillis,
      "image"             -> o.image,
      "network_enabled"   -> o.networkEnabled,
      "allow_cidrs"       -> o.allowCidrs,
      "cpus"              -> o.cpus,
      "memory_mb"         -> o.memoryMb,
      "storage_gb"        -> o.storageGb.map(v => Json.toJson(v)).getOrElse(JsNull).as[JsValue],
      "overlay_gb"        -> o.overlayGb.map(v => Json.toJson(v)).getOrElse(JsNull).as[JsValue],
      "gpu"               -> o.gpu,
      "env"               -> o.env,
      "volumes"           -> o.volumes,
      "ports"             -> o.ports,
      "workdir"           -> o.workdir.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "service_port"      -> o.servicePort,
      "readiness_path"    -> o.readinessPath,
      "readiness_timeout" -> o.readinessTimeout.toMillis,
      "exec_command"      -> o.execCommand.map(s => Json.toJson(s)).getOrElse(JsNull).as[JsValue],
      "request_timeout"   -> o.requestTimeout.toMillis,
      "boot_timeout"      -> o.bootTimeout.toMillis,
      "isolation"         -> o.isolation
    )

    override def reads(json: JsValue): JsResult[SmolVmFunctionConfig] = Try {
      def ms(key: String, default: FiniteDuration): FiniteDuration =
        (json \ key).asOpt[Long].map(_.milliseconds).getOrElse(default)
      val rawMode      = (json \ "mode").asOpt[String].map(_.trim.toLowerCase).getOrElse("service")
      val mode         = if (rawMode == "exec") "exec" else "service"
      val rawIsolation = (json \ "isolation").asOpt[String].map(_.trim.toLowerCase).getOrElse("ephemeral")
      val isolation    = if (rawIsolation == "reuse") "reuse" else "ephemeral"
      SmolVmFunctionConfig(
        mode = mode,
        hosts = (json \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty),
        hostsUrl = (json \ "hosts_url").asOpt[String].filter(_.trim.nonEmpty),
        hostsRefresh = ms("hosts_refresh", 60.seconds),
        image = (json \ "image").asOpt[String].getOrElse(""),
        networkEnabled = (json \ "network_enabled").asOpt[Boolean].getOrElse(false),
        allowCidrs = (json \ "allow_cidrs").asOpt[Seq[String]].getOrElse(Seq.empty),
        cpus = (json \ "cpus").asOpt[Int].getOrElse(2),
        memoryMb = (json \ "memory_mb").asOpt[Int].getOrElse(512),
        storageGb = (json \ "storage_gb").asOpt[Int],
        overlayGb = (json \ "overlay_gb").asOpt[Int],
        gpu = (json \ "gpu").asOpt[Boolean].getOrElse(false),
        env = (json \ "env").asOpt[Map[String, String]].getOrElse(Map.empty),
        volumes = (json \ "volumes").asOpt[Seq[String]].getOrElse(Seq.empty),
        ports = (json \ "ports").asOpt[Seq[String]].getOrElse(Seq.empty),
        workdir = (json \ "workdir").asOpt[String].filter(_.trim.nonEmpty),
        servicePort = (json \ "service_port").asOpt[Int].getOrElse(8080),
        readinessPath = (json \ "readiness_path").asOpt[String].getOrElse("/"),
        readinessTimeout = ms("readiness_timeout", 10.seconds),
        execCommand = (json \ "exec_command").asOpt[Seq[String]].filter(_.nonEmpty),
        requestTimeout = ms("request_timeout", 30.seconds),
        bootTimeout = ms("boot_timeout", 60.seconds),
        isolation = isolation
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }

  val configFlow: Seq[String] = Seq(
    "mode",
    "image",
    "hosts",
    "hosts_url",
    "hosts_refresh",
    "network_enabled",
    "allow_cidrs",
    "cpus",
    "memory_mb",
    "storage_gb",
    "overlay_gb",
    "gpu",
    "env",
    "volumes",
    "ports",
    "workdir",
    "service_port",
    "readiness_path",
    "readiness_timeout",
    "exec_command",
    "request_timeout",
    "boot_timeout",
    "isolation"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "mode"              -> Json.obj("type" -> "select", "label" -> "Execution mode", "props" -> Json.obj(
        "options" -> Json.arr(
          Json.obj("value" -> "service", "label" -> "service (HTTP server in VM, proxied)"),
          Json.obj("value" -> "exec", "label" -> "exec (stdin JSON -> stdout JSON)")
        )
      )),
      "image"             -> Json.obj("type" -> "string", "label" -> "Image (OCI ref / archive / rootfs)"),
      "hosts"             -> Json.obj("type" -> "array", "label" -> "smolvm hosts", "props" -> Json.obj("placeholder" -> "http://host:8080")),
      "hosts_url"         -> Json.obj("type" -> "string", "label" -> "Hosts URL (JSON array)"),
      "hosts_refresh"     -> Json.obj("type" -> "number", "label" -> "Hosts refresh", "props" -> Json.obj("suffix" -> "ms")),
      "network_enabled"   -> Json.obj("type" -> "bool", "label" -> "Network enabled (outbound)"),
      "allow_cidrs"       -> Json.obj("type" -> "array", "label" -> "Egress allowed CIDRs"),
      "cpus"              -> Json.obj("type" -> "number", "label" -> "vCPUs"),
      "memory_mb"         -> Json.obj("type" -> "number", "label" -> "Memory", "props" -> Json.obj("suffix" -> "MiB")),
      "storage_gb"        -> Json.obj("type" -> "number", "label" -> "Storage", "props" -> Json.obj("suffix" -> "GiB")),
      "overlay_gb"        -> Json.obj("type" -> "number", "label" -> "Overlay", "props" -> Json.obj("suffix" -> "GiB")),
      "gpu"               -> Json.obj("type" -> "bool", "label" -> "GPU"),
      "env"               -> Json.obj("type" -> "object", "label" -> "Env. variables (exec mode only)"),
      "volumes"           -> Json.obj("type" -> "array", "label" -> "Mounts (hostPath:guestPath[:ro])"),
      "ports"             -> Json.obj("type" -> "array", "label" -> "Extra ports (host:guest)"),
      "workdir"           -> Json.obj("type" -> "string", "label" -> "Working directory (exec mode)"),
      "service_port"      -> Json.obj("type" -> "number", "label" -> "Service port (mode service)"),
      "readiness_path"    -> Json.obj("type" -> "string", "label" -> "Readiness path (mode service)"),
      "readiness_timeout" -> Json.obj("type" -> "number", "label" -> "Readiness timeout", "props" -> Json.obj("suffix" -> "ms")),
      "exec_command"      -> Json.obj("type" -> "array", "label" -> "Exec command (mode exec)"),
      "request_timeout"   -> Json.obj("type" -> "number", "label" -> "Request timeout", "props" -> Json.obj("suffix" -> "ms")),
      "boot_timeout"      -> Json.obj("type" -> "number", "label" -> "Boot timeout", "props" -> Json.obj("suffix" -> "ms")),
      "isolation"         -> Json.obj("type" -> "select", "label" -> "Isolation", "props" -> Json.obj(
        "options" -> Json.arr(
          Json.obj("value" -> "ephemeral", "label" -> "ephemeral (VM per request)"),
        )
      ))
    )
  )
}

// -----------------------------------------------------------------------------
// Plugin
// -----------------------------------------------------------------------------

object SmolVmFunctionBackend {
  private val engineRef = new AtomicReference[SmolVmEngine]()

  def engine(env: Env): SmolVmEngine = {
    val current = engineRef.get()
    if (current != null) current
    else {
      engineRef.compareAndSet(null, new SmolVmEngine(env))
      engineRef.get()
    }
  }
}

class SmolVmFunctionBackend extends NgBackendCall {

  private val logger = Logger("cloud-apim-smolvm")

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           =
    Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Integrations)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def useDelegates: Boolean                       = false
  override def name: String                                = "Cloud APIM - smolvm ephemeral"
  override def description: Option[String]                 =
    "Run a function in a smolvm micro-VM (HTTP-service proxy or stdin/stdout exec) and return its response".some
  override def defaultConfigObject: Option[NgPluginConfig] = SmolVmFunctionConfig.default.some
  override def configFlow: Seq[String]                     = SmolVmFunctionConfig.configFlow
  override def configSchema: Option[JsObject]              = SmolVmFunctionConfig.configSchema
  override def noJsForm: Boolean = true

  override def start(env: Env): Future[Unit] = {
    logger.info("[smolvm] plugin loading: instantiating SmolVmEngine singleton")
    SmolVmFunctionBackend.engine(env)
    env.logger.info("[Cloud APIM] the 'smolvm ephemeral' plugin is available!")
    logger.info("[smolvm] plugin ready (logger name: 'cloud-apim-smolvm' — set it to DEBUG for HTTP-level traces)")
    Future.successful(())
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(SmolVmFunctionConfig.format).getOrElse(SmolVmFunctionConfig.default)
    val startMs = System.currentTimeMillis()
    logger.info(
      s"[${ctx.snowflake}] callBackend ENTER route='${ctx.route.name}' ${ctx.request.method} ${ctx.request.relativeUri} mode=${config.mode}"
    )
    // Consume the incoming body exactly once, and ONLY if there is one. The request
    // Source is single-subscriber; for bodyless requests (e.g. GET) the pipeline may
    // already have drained it, so touching it again throws "Sink.asPublisher ... one
    // subscriber". We hand the engine a buffered ByteString.
    val bodyF: Future[ByteString] =
      if (ctx.request.hasBody) ctx.request.body.runFold(ByteString.empty)(_ ++ _)
      else Future.successful(ByteString.empty)
    bodyF
      .map { bodyBytes =>
        SmolInvocation(
          snowflake = ctx.snowflake,
          method = ctx.request.method,
          relativeUri = ctx.request.relativeUri,
          path = ctx.request.path,
          query = ctx.request.queryParams,
          headers = ctx.request.headers,
          bodyBytes = bodyBytes
        )
      }
      .flatMap(inv => SmolVmFunctionBackend.engine(env).invoke(inv, config))
      .flatMap {
        case InvokeResult.Streamed(status, headers, body) =>
          logger.info(s"[${ctx.snowflake}] callBackend EXIT streamed status=$status (${System.currentTimeMillis() - startMs}ms)")
          Future.successful(sourceBodyResponse(status, headers, body))
        case InvokeResult.Buffered(status, headers, body) =>
          logger.info(s"[${ctx.snowflake}] callBackend EXIT buffered status=$status bytes=${body.length} (${System.currentTimeMillis() - startMs}ms)")
          Future.successful(inMemoryBodyResponse(status, headers, body))
        case InvokeResult.Failed(status, message)         =>
          logger.warn(s"[${ctx.snowflake}] callBackend EXIT failed status=$status msg='$message' (${System.currentTimeMillis() - startMs}ms)")
          Errors
            .craftResponseResult(
              message,
              Results.Status(status),
              ctx.rawRequest,
              None,
              None,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(r => Left(NgProxyEngineError.NgResultProxyEngineError(r)))
      }
      .recover {
        case e: Throwable =>
          logger.error(s"[${ctx.snowflake}] smolvm plugin error on backend call", e)
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> e.getMessage))))
      }
  }
}

package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.smolvm.client.{InvokeResult, SmolInvocation}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.SmolMachineExtension
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// -----------------------------------------------------------------------------
// Configuration — only a reference to a SmolMachine entity. Everything else
// (image, mode, instances, runtime, hosts, timeouts) lives on the entity.
// -----------------------------------------------------------------------------

case class SmolMachineBackendConfig(ref: String = "") extends NgPluginConfig {
  def json: JsValue = SmolMachineBackendConfig.format.writes(this)
}

object SmolMachineBackendConfig {

  val default = SmolMachineBackendConfig()

  val format: Format[SmolMachineBackendConfig] = new Format[SmolMachineBackendConfig] {
    override def writes(o: SmolMachineBackendConfig): JsValue = Json.obj("ref" -> o.ref)
    override def reads(json: JsValue): JsResult[SmolMachineBackendConfig] = Try {
      SmolMachineBackendConfig(ref = (json \ "ref").asOpt[String].getOrElse(""))
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }

  val configFlow: Seq[String] = Seq("ref")

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "ref" -> Json.obj(
        "type"  -> "select",
        "label" -> "Smol Machine",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/apis/smolvm.extensions.cloud-apim.com/v1/smol-machines",
          "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
        )
      )
    )
  )
}

// -----------------------------------------------------------------------------
// Plugin
// -----------------------------------------------------------------------------

class SmolMachineBackend extends NgBackendCall {

  private val logger = Logger("cloud-apim-smolmachine")

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           =
    Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Integrations)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def useDelegates: Boolean                       = false
  override def name: String                                = "Cloud APIM - smolvm Machine"
  override def description: Option[String]                 =
    "Route a request to a (persistent, pooled) smolvm machine referenced by a SmolMachine entity".some
  override def defaultConfigObject: Option[NgPluginConfig] = SmolMachineBackendConfig.default.some
  override def configFlow: Seq[String]                     = SmolMachineBackendConfig.configFlow
  override def configSchema: Option[JsObject]              = SmolMachineBackendConfig.configSchema
  override def noJsForm: Boolean                           = true

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[SmolMachineExtension] match {
      case Some(_) => logger.info("[smolmachine] the 'smolvm Machine' plugin is available!")
      case None    => logger.warn("[smolmachine] the 'smolvm Machine' plugin is loaded but the SmolMachine admin extension is not enabled")
    }
    Future.successful(())
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val config = ctx.cachedConfig(internalName)(SmolMachineBackendConfig.format).getOrElse(SmolMachineBackendConfig.default)

    def fail(status: Int, message: String): Future[Either[NgProxyEngineError, BackendCallResponse]] =
      Errors
        .craftResponseResult(message, Results.Status(status), ctx.rawRequest, None, None, attrs = ctx.attrs, maybeRoute = ctx.route.some)
        .map(r => Left(NgProxyEngineError.NgResultProxyEngineError(r)))

    val extOpt     = env.adminExtensions.extension[SmolMachineExtension]
    val machineOpt = extOpt.flatMap(_.smolMachine(config.ref))

    (extOpt, machineOpt) match {
      case (None, _)             => fail(500, "the SmolMachine admin extension is not enabled")
      case (_, None)             => fail(404, s"smol machine '${config.ref}' not found")
      case (Some(ext), Some(machine)) =>
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
          .flatMap(inv => ext.manager.invoke(machine, inv))
          .flatMap {
            case InvokeResult.Streamed(status, headers, body) => Future.successful(sourceBodyResponse(status, headers, body))
            case InvokeResult.Buffered(status, headers, body) => Future.successful(inMemoryBodyResponse(status, headers, body))
            case InvokeResult.Failed(status, message)         => fail(status, message)
          }
          .recover {
            case e: Throwable =>
              logger.error(s"[${ctx.snowflake}] smolvm machine plugin error", e)
              Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> e.getMessage))))
          }
    }
  }
}

package otoroshi_plugins.com.cloud.apim.plugins.smolvm

import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** Headers + status + streamed body returned by a `service`-mode proxy call. */
case class SmolProxyResponse(status: Int, headers: Map[String, String], body: Source[ByteString, _])

/**
 * Stateless HTTP wrapper over the smolvm local API (`/api/v1`). One instance is reused
 * for every host; the host base URL is passed per call. See SPEC.md §2.1.
 */
class SmolVmClient(env: Env) {

  private val logger = Logger("cloud-apim-smolvm")

  private def api(host: String, path: String): String = s"${host.stripSuffix("/")}/api/v1$path"

  private def okUnit(action: String)(r: WSResponse): Either[String, Unit] = {
    logger.debug(s"smolvm $action -> HTTP ${r.status}")
    if (r.status >= 200 && r.status < 300) Right(())
    else Left(s"$action returned ${r.status}: ${r.body.take(512)}")
  }

  /** Liveness of a host: any non-5xx answer on the machines list. */
  def health(host: String, timeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Boolean] =
    env.Ws
      .url(api(host, "/machines"))
      .withRequestTimeout(timeout)
      .get()
      .map(r => r.status >= 200 && r.status < 500)
      .recover { case _ => false }

  def createMachine(host: String, spec: SmolMachineSpec, timeout: FiniteDuration)(implicit
      ec: ExecutionContext
  ): Future[Either[String, Unit]] =
    env.Ws
      .url(api(host, "/machines"))
      .withRequestTimeout(timeout)
      .withHttpHeaders("Content-Type" -> "application/json")
      .withMethod("POST")
      .withBody(ByteString(Json.stringify(spec.json)))
      .execute()
      .map(okUnit("create machine"))
      .recover { case e => Left(s"create machine failed: ${e.getMessage}") }

  def start(host: String, name: String, timeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Either[String, Unit]] =
    env.Ws
      .url(api(host, s"/machines/$name/start"))
      .withRequestTimeout(timeout)
      .withMethod("POST")
      .withBody(ByteString.empty)
      .execute()
      .map { r =>
        // tolerate 409: some setups auto-start the machine on create
        if ((r.status >= 200 && r.status < 300) || r.status == 409) Right(())
        else Left(s"start machine returned ${r.status}: ${r.body.take(512)}")
      }
      .recover { case e => Left(s"start machine failed: ${e.getMessage}") }

  def delete(host: String, name: String, timeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Either[String, Unit]] =
    env.Ws
      .url(api(host, s"/machines/$name"))
      .withRequestTimeout(timeout)
      .withMethod("DELETE")
      .execute()
      .map(okUnit("delete machine"))
      .recover { case e => Left(s"delete machine failed: ${e.getMessage}") }

  def exec(host: String, name: String, req: ExecRequest, timeout: FiniteDuration)(implicit
      ec: ExecutionContext
  ): Future[Either[String, ExecResponse]] = {
    logger.debug(s"POST ${api(host, s"/machines/$name/exec")} command=[${req.command.mkString(" ")}] stdin=${req.stdin.fold(0)(_.length)}b")
    env.Ws
      .url(api(host, s"/machines/$name/exec"))
      .withRequestTimeout(timeout)
      .withHttpHeaders("Content-Type" -> "application/json")
      .withMethod("POST")
      .withBody(ByteString(Json.stringify(req.json)))
      .execute()
      .map { r =>
        logger.debug(s"smolvm exec -> HTTP ${r.status}")
        if (r.status >= 200 && r.status < 300) ExecResponse.reads(r.json).asEither.left.map(e => s"invalid exec response: $e")
        else Left(s"exec returned ${r.status}: ${r.body.take(512)}")
      }
      .recover { case e => Left(s"exec failed: ${e.getMessage}") }
  }

  /** Pull an OCI image into a machine (idempotent; fast when cached host-side). */
  def pullImage(host: String, name: String, image: String, timeout: FiniteDuration)(implicit
      ec: ExecutionContext
  ): Future[Either[String, Unit]] =
    env.Ws
      .url(api(host, s"/machines/$name/images/pull"))
      .withRequestTimeout(timeout)
      .withHttpHeaders("Content-Type" -> "application/json")
      .withMethod("POST")
      .withBody(ByteString(Json.stringify(Json.obj("image" -> image))))
      .execute()
      .map(okUnit("pull image"))
      .recover { case e => Left(s"pull image failed: ${e.getMessage}") }

  /** Probe a forwarded service port: any HTTP answer means the server is listening. */
  def probe(serviceUrl: String, timeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Boolean] =
    env.Ws
      .url(serviceUrl)
      .withRequestTimeout(timeout)
      .get()
      .map(_ => true)
      .recover { case _ => false }

  /** Reverse-proxy to a forwarded service port, streaming both ways. */
  def proxy(url: String, method: String, headers: Map[String, String], body: Source[ByteString, _], timeout: FiniteDuration)(
      implicit ec: ExecutionContext
  ): Future[SmolProxyResponse] = {
    logger.debug(s"PROXY $method $url")
    env.Ws
      .url(url)
      .withRequestTimeout(timeout)
      .withMethod(method)
      .withHttpHeaders(headers.toSeq: _*)
      .withBody(body)
      .stream()
      .map { resp =>
        logger.debug(s"smolvm proxy $method $url -> HTTP ${resp.status}")
        SmolProxyResponse(resp.status, resp.headers.mapValues(_.last).toMap, resp.bodyAsSource)
      }
  }
}

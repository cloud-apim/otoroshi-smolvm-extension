package otoroshi_plugins.com.cloud.apim.plugins.smolvm

import play.api.libs.json._

import scala.util.{Failure, Success, Try}

/**
 * Models mirroring the smolvm local HTTP API (`/api/v1`), validated against the
 * OpenAPI of smolvm 1.0.4 (`smolvm serve openapi`). See SPEC.md §2.
 */

/** Item of `mounts` in CreateMachineRequest (MountSpec). */
case class SmolMount(source: String, target: String, readonly: Boolean = false) {
  def json: JsObject = Json.obj("source" -> source, "target" -> target, "readonly" -> readonly)
}

/** Item of `ports` in CreateMachineRequest (PortSpec). host/guest are integers. */
case class SmolPort(host: Int, guest: Int) {
  def json: JsObject = Json.obj("host" -> host, "guest" -> guest)
}

/** Body of `POST /api/v1/machines` (CreateMachineRequest). Only image (or `from`) is meaningful. */
case class SmolMachineSpec(
    name: String,
    image: String,
    cpus: Option[Int] = None,
    memoryMb: Option[Int] = None,
    storageGb: Option[Int] = None,
    overlayGb: Option[Int] = None,
    network: Boolean = false,
    networkBackend: Option[String] = None, // "tsi" (default) | "virtio-net" (required for published ports)
    gpu: Boolean = false,
    allowedCidrs: Seq[String] = Seq.empty,  // egress CIDR allowlist (smolvm has no host-based egress)
    mounts: Seq[SmolMount] = Seq.empty,
    ports: Seq[SmolPort] = Seq.empty
) {
  def json: JsValue = {
    var o = Json.obj("name" -> name, "image" -> image, "network" -> network, "gpu" -> gpu)
    cpus.foreach(v => o = o ++ Json.obj("cpus" -> v))
    memoryMb.foreach(v => o = o ++ Json.obj("memoryMb" -> v))
    storageGb.foreach(v => o = o ++ Json.obj("storageGb" -> v))
    overlayGb.foreach(v => o = o ++ Json.obj("overlayGb" -> v))
    networkBackend.foreach(v => o = o ++ Json.obj("networkBackend" -> v))
    if (allowedCidrs.nonEmpty) o = o ++ Json.obj("allowedCidrs" -> allowedCidrs)
    if (mounts.nonEmpty) o = o ++ Json.obj("mounts" -> JsArray(mounts.map(_.json)))
    if (ports.nonEmpty) o = o ++ Json.obj("ports" -> JsArray(ports.map(_.json)))
    o
  }
}

/** Body of `POST /api/v1/machines/:name/exec`. `stdin` is supported by smolvm 1.0.4. */
case class ExecRequest(
    command: Seq[String],
    stdin: Option[String] = None,
    env: Seq[(String, String)] = Seq.empty,
    workdir: Option[String] = None,
    timeoutSecs: Option[Long] = None
) {
  def json: JsValue = {
    var o = Json.obj("command" -> command)
    stdin.foreach(v => o = o ++ Json.obj("stdin" -> v))
    if (env.nonEmpty)
      o = o ++ Json.obj("env" -> JsArray(env.map { case (k, v) => Json.obj("name" -> k, "value" -> v) }))
    workdir.foreach(v => o = o ++ Json.obj("workdir" -> v))
    timeoutSecs.foreach(v => o = o ++ Json.obj("timeoutSecs" -> v))
    o
  }
}

/** Response of `POST /api/v1/machines/:name/exec` (ExecResponse: exitCode/stdout/stderr). */
case class ExecResponse(stdout: String, stderr: String, exitCode: Int) {
  def success: Boolean = exitCode == 0
}

object ExecResponse {
  def reads(json: JsValue): JsResult[ExecResponse] = Try {
    ExecResponse(
      stdout = (json \ "stdout").asOpt[String].getOrElse(""),
      stderr = (json \ "stderr").asOpt[String].getOrElse(""),
      exitCode = (json \ "exitCode").asOpt[Int].getOrElse(0)
    )
  } match {
    case Failure(e) => JsError(e.getMessage)
    case Success(r) => JsSuccess(r)
  }
}

/**
 * Envelope used by the `exec` execution contract: the incoming HTTP request is
 * serialized to JSON and written to the function's stdin; the function writes a
 * response envelope to stdout. See SPEC.md §4.2.
 */
object ExecEnvelope {

  def requestJson(method: String, path: String, query: Map[String, String], headers: Map[String, String], bodyBase64: String): JsValue =
    Json.obj(
      "method"      -> method,
      "path"        -> path,
      "query"       -> query,
      "headers"     -> headers,
      "body_base64" -> bodyBase64
    )

  /** Parsed function response. `body` is the decoded bytes (from `body_base64` or `body`). */
  case class FunctionResponse(status: Int, headers: Map[String, String], body: Array[Byte])

  def parseResponse(stdout: String): Either[String, FunctionResponse] = {
    Try(Json.parse(stdout)) match {
      case Failure(e)    => Left(s"invalid JSON on stdout: ${e.getMessage}")
      case Success(json) =>
        val status  = (json \ "status").asOpt[Int].getOrElse(200)
        val headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
        val body: Array[Byte] = (json \ "body_base64").asOpt[String] match {
          case Some(b64) => Try(java.util.Base64.getDecoder.decode(b64)).getOrElse(Array.emptyByteArray)
          case None      => (json \ "body").asOpt[String].map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8)).getOrElse(Array.emptyByteArray)
        }
        Right(FunctionResponse(status, headers, body))
    }
  }
}

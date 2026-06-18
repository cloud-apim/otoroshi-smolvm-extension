package com.cloud.apim.otoroshi.extensions.smolvm.workflows

import akka.util.ByteString
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.SmolMachineExtension
import com.cloud.apim.otoroshi.extensions.smolvm.client.{InvokeResult, SmolInvocation}
import com.cloud.apim.otoroshi.extensions.smolvm.entities.SmolMachine
import otoroshi.env.Env
import otoroshi.next.workflow.{WorkflowError, WorkflowFunction, WorkflowRun}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object SmolMachineWorkflowFunctions {

  val callName    = "extensions.com.cloud-apim.smolmachine.call"
  val runCodeName = "extensions.com.cloud-apim.smolmachine.run_code"

  def registerAll(): Unit = {
    WorkflowFunction.registerFunction(callName, new SmolMachineCallFunction())
    WorkflowFunction.registerFunction(runCodeName, new SmolMachineRunCodeFunction())
  }

  // ---- shared helpers -------------------------------------------------------

  def resolve(ref: String)(implicit env: Env): Either[WorkflowError, (SmolMachineExtension, SmolMachine)] =
    env.adminExtensions.extension[SmolMachineExtension] match {
      case None      => Left(WorkflowError("the SmolMachine extension is not enabled", None, None))
      case Some(ext) =>
        ext.smolMachine(ref) match {
          case None    => Left(WorkflowError(s"smol machine '$ref' not found", Some(Json.obj("ref" -> ref)), None))
          case Some(m) => Right((ext, m))
        }
    }

  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  def invocation(method: String, path: String, query: Map[String, String], headers: Map[String, String], body: ByteString): SmolInvocation = {
    val rel = if (query.isEmpty) path else path + "?" + query.map { case (k, v) => s"${enc(k)}=${enc(v)}" }.mkString("&")
    SmolInvocation(s"wf-${java.util.UUID.randomUUID().toString}", method, rel, path, query, headers, body)
  }
}

/** `smolmachine.call` — send an HTTP-like request to a SmolMachine and return its response. */
class SmolMachineCallFunction extends WorkflowFunction {
  override def documentationName: String                   = SmolMachineWorkflowFunctions.callName
  override def documentationDisplayName: String            = "Smol Machine call"
  override def documentationIcon: String                   = "fas fa-microchip"
  override def documentationDescription: String            =
    "Send a request to a SmolMachine (service / exec / node|bun runtime) and return its response"
  override def documentationInputSchema: Option[JsObject]  = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("ref"),
      "properties" -> Json.obj(
        "ref"         -> Json.obj("type" -> "string", "description" -> "The SmolMachine id"),
        "method"      -> Json.obj("type" -> "string", "description" -> "HTTP method (default GET)"),
        "path"        -> Json.obj("type" -> "string", "description" -> "Request path (default /)"),
        "query"       -> Json.obj("type" -> "object", "description" -> "Query params"),
        "headers"     -> Json.obj("type" -> "object", "description" -> "Request headers"),
        "body"        -> Json.obj("type" -> "string", "description" -> "Request body (string)"),
        "body_json"   -> Json.obj("type" -> "object", "description" -> "Request body (json)"),
        "body_base64" -> Json.obj("type" -> "string", "description" -> "Request body (base64)")
      )
    )
  )
  override def documentationExample: Option[JsObject]      = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> SmolMachineWorkflowFunctions.callName,
      "args"     -> Json.obj("ref" -> "smol-machine_xxx", "method" -> "POST", "path" -> "/", "body_json" -> Json.obj("hello" -> "world"))
    )
  )

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    SmolMachineWorkflowFunctions.resolve(args.select("ref").asString) match {
      case Left(err)             => err.leftf
      case Right((ext, machine)) =>
        val method  = args.select("method").asOptString.getOrElse("GET")
        val path    = args.select("path").asOptString.getOrElse("/")
        val query   = args.select("query").asOpt[Map[String, String]].getOrElse(Map.empty)
        val headers = args.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
        val body: ByteString = args.select("body_base64").asOptString.map(b => ByteString(java.util.Base64.getDecoder.decode(b)))
          .orElse(args.select("body_json").asOpt[JsValue].map(j => ByteString(Json.stringify(j).getBytes(StandardCharsets.UTF_8))))
          .orElse(args.select("body").asOptString.map(s => ByteString(s.getBytes(StandardCharsets.UTF_8))))
          .getOrElse(ByteString.empty)
        ext.manager.invoke(machine, SmolMachineWorkflowFunctions.invocation(method, path, query, headers, body)).map {
          case InvokeResult.Buffered(status, hdrs, b) =>
            val bodyStr        = b.utf8String
            val bj: JsValue    = Try(Json.parse(bodyStr)).toOption.getOrElse(JsNull)
            Right(Json.obj("status" -> status, "headers" -> hdrs, "body" -> bodyStr, "body_json" -> bj))
          case InvokeResult.Streamed(status, hdrs, _) =>
            Right(Json.obj("status" -> status, "headers" -> hdrs, "body" -> "", "body_json" -> JsNull))
          case InvokeResult.Failed(status, message)   =>
            Left(WorkflowError(message, Some(Json.obj("status" -> status, "ref" -> machine.id)), None))
        }.recover { case t => Left(WorkflowError(s"smol machine call failed: ${t.getMessage}", None, Some(t))) }
    }
  }
}

/** `smolmachine.run_code` — run JS code on a node/bun SmolMachine and return `{exitCode,stdout,stderr}`. */
class SmolMachineRunCodeFunction extends WorkflowFunction {
  override def documentationName: String                  = SmolMachineWorkflowFunctions.runCodeName
  override def documentationDisplayName: String           = "Smol Machine run code"
  override def documentationIcon: String                  = "fas fa-code"
  override def documentationDescription: String           =
    "Run JS code on a node/bun SmolMachine (runtime node|bun, mode exec) and return {exitCode, stdout, stderr}"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("ref", "code"),
      "properties" -> Json.obj(
        "ref"     -> Json.obj("type" -> "string", "description" -> "The SmolMachine id (runtime node|bun, mode exec)"),
        "code"    -> Json.obj("type" -> "string", "description" -> "JS code to run"),
        "esm"     -> Json.obj("type" -> "boolean", "description" -> "Run as ES module"),
        "env"     -> Json.obj("type" -> "object", "description" -> "Extra environment variables"),
        "workdir" -> Json.obj("type" -> "string", "description" -> "Working directory"),
        "timeout" -> Json.obj("type" -> "number", "description" -> "Timeout in milliseconds")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> SmolMachineWorkflowFunctions.runCodeName,
      "args"     -> Json.obj("ref" -> "smol-machine_node", "code" -> "console.log(40+2)")
    )
  )

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    SmolMachineWorkflowFunctions.resolve(args.select("ref").asString) match {
      case Left(err)             => err.leftf
      case Right((ext, machine)) =>
        val code: String = args.select("code").asOptString.getOrElse("")
        val payload = Json.obj("code" -> code) ++
          args.select("esm").asOpt[Boolean].map(b => Json.obj("esm" -> b)).getOrElse(Json.obj()) ++
          args.select("env").asOpt[JsObject].map(o => Json.obj("env" -> o)).getOrElse(Json.obj()) ++
          args.select("workdir").asOptString.map(s => Json.obj("workdir" -> s)).getOrElse(Json.obj()) ++
          args.select("timeout").asOpt[Long].map(l => Json.obj("timeout" -> l)).getOrElse(Json.obj())
        val inv = SmolMachineWorkflowFunctions.invocation("POST", "/run", Map.empty, Map("content-type" -> "application/json"), ByteString(Json.stringify(payload).getBytes(StandardCharsets.UTF_8)))
        ext.manager.invoke(machine, inv).map {
          case InvokeResult.Buffered(status, _, b) =>
            val bj = Try(Json.parse(b.utf8String)).toOption.getOrElse(JsNull)
            if (status >= 200 && status < 300) Right(bj)
            else Left(WorkflowError(s"run_code failed (status $status)", Some(Json.obj("status" -> status, "body" -> bj)), None))
          case InvokeResult.Streamed(_, _, _)      => Left(WorkflowError("unexpected streamed response from run_code", None, None))
          case InvokeResult.Failed(status, message) => Left(WorkflowError(message, Some(Json.obj("status" -> status, "ref" -> machine.id)), None))
        }.recover { case t => Left(WorkflowError(s"run_code failed: ${t.getMessage}", None, Some(t))) }
    }
  }
}

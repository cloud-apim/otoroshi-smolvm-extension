package com.cloud.apim.otoroshi.extensions.smolvm.runtime

import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.smolvm.client.{InvokeResult, SmolInvocation, SmolVmClient}
import com.cloud.apim.otoroshi.extensions.smolvm.entities.{ExecRequest, ExecResponse, SmolMachineSpec}
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Node runtime RPC facade (`spec.runtime == "node"`, exec mode). Maps an incoming HTTP request
 * to a `node`/`npm`/`npx` exec on a live `node:22-alpine` machine and returns the exec result
 * as JSON `{exitCode, stdout, stderr}`. Mirrors the `smolvm-sdk` node preset
 * (runCode/runESM/runFile/npm/npmInstall/npx/version) over HTTP.
 *
 * Routed by the tail of the request path:
 *   POST .../run         {code, esm?, env?, workdir?, timeout?}  -> node -e <code>  (or --input-type=module)
 *   POST .../eval        {expression}                            -> node -e console.log(JSON.stringify((expr)))
 *   POST .../run-file    {path}                                  -> node <path>
 *   POST .../npm         {args:[...]}                            -> npm <args>
 *   POST .../npm/install {packages:[...]}                        -> npm install <packages>
 *   POST .../npx         {args:[...]}                            -> npx <args>
 *   GET  .../version                                             -> node --version
 *   PUT  .../files/<path> (raw body)                             -> upload a file into the machine
 */
object NodeRuntime {

  private val logger = Logger("cloud-apim-smolmachine")

  def handle(client: SmolVmClient, host: String, name: String, spec: SmolMachineSpec, inv: SmolInvocation)(implicit
      ec: ExecutionContext
  ): Future[InvokeResult] = {
    val path     = inv.path.stripSuffix("/")
    val bodyJson = Try(Json.parse(inv.bodyBytes.utf8String)).toOption.getOrElse(Json.obj())
    val bodyEnv  = (bodyJson \ "env").asOpt[Map[String, String]].getOrElse(Map.empty)
    val execEnv  = (spec.env ++ bodyEnv).toSeq
    val workdir  = (bodyJson \ "workdir").asOpt[String].orElse(spec.workdir)
    val timeout  = (bodyJson \ "timeout").asOpt[Long].map(_.milliseconds).getOrElse(spec.requestTimeout)

    def run(cmd: Seq[String], stdin: Option[String] = None): Future[InvokeResult] = {
      logger.info(s"[$name] node rpc: ${cmd.mkString(" ")}")
      client
        .exec(host, name, ExecRequest(cmd, stdin, execEnv, workdir, Some(timeout.toSeconds)), timeout)
        .map(toResult)
    }

    def strList(field: String): Seq[String] = (bodyJson \ field).asOpt[Seq[String]].getOrElse(Seq.empty)

    if (inv.method.equalsIgnoreCase("PUT") && path.contains("/files/")) {
      val filePath = "/" + inv.path.split("/files/", 2).drop(1).headOption.getOrElse("").stripPrefix("/")
      logger.info(s"[$name] node rpc: PUT file $filePath (${inv.bodyBytes.length}b)")
      client.putFile(host, name, filePath, inv.bodyBytes, timeout).map {
        case Right(_)  => InvokeResult.Buffered(200, Map("content-type" -> "application/json"), ByteString(Json.stringify(Json.obj("done" -> true, "path" -> filePath))))
        case Left(err) => InvokeResult.Failed(502, err)
      }
    } else if (path.endsWith("/run")) {
      val code = (bodyJson \ "code").asOpt[String].getOrElse("")
      val esm  = (bodyJson \ "esm").asOpt[Boolean].getOrElse(false)
      run(if (esm) Seq("node", "--input-type=module", "-e", code) else Seq("node", "-e", code))
    } else if (path.endsWith("/eval")) {
      val expr = (bodyJson \ "expression").asOpt[String].getOrElse("")
      run(Seq("node", "-e", s"console.log(JSON.stringify(($expr)))"))
    } else if (path.endsWith("/run-file")) {
      val file = (bodyJson \ "path").asOpt[String].getOrElse("")
      run(Seq("node", file))
    } else if (path.endsWith("/npm/install")) {
      run(Seq("npm", "install") ++ strList("packages"))
    } else if (path.endsWith("/npm")) {
      run(Seq("npm") ++ strList("args"))
    } else if (path.endsWith("/npx")) {
      run(Seq("npx") ++ strList("args"))
    } else if (path.endsWith("/version")) {
      run(Seq("node", "--version"))
    } else {
      Future.successful(
        InvokeResult.Failed(
          404,
          s"unknown node runtime operation for path '${inv.path}' (try /run, /eval, /run-file, /npm, /npm/install, /npx, /version, PUT /files/<path>)"
        )
      )
    }
  }

  private def toResult(e: Either[String, ExecResponse]): InvokeResult = e match {
    case Left(err) => InvokeResult.Failed(502, err)
    case Right(r)  =>
      InvokeResult.Buffered(
        200,
        Map("content-type" -> "application/json"),
        ByteString(Json.stringify(Json.obj("exitCode" -> r.exitCode, "stdout" -> r.stdout, "stderr" -> r.stderr)))
      )
  }
}

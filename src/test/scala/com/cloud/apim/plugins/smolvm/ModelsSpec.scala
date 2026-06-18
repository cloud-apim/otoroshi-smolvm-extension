package com.cloud.apim.plugins.smolvm

import com.cloud.apim.otoroshi.extensions.smolvm.entities.{ExecEnvelope, ExecRequest, ExecResponse, SmolMachineSpecV1, SmolMount, SmolPort}
import otoroshi_plugins.com.cloud.apim.plugins.smolvm._
import play.api.libs.json.Json

class ModelsSpec extends munit.FunSuite {

  test("SmolMachineSpec.json maps fields to the smolvm 1.0.4 create body") {
    val spec = SmolMachineSpecV1(
      name = "otoroshi-fn-123",
      image = "alpine",
      cpus = Some(2),
      memoryMb = Some(512),
      network = true,
      networkBackend = Some("virtio-net"),
      allowedCidrs = Seq("10.0.0.0/8"),
      ports = Seq(SmolPort(18080, 8080)),
      mounts = Seq(SmolMount("/host/src", "/app"))
    )
    val j = spec.json
    assertEquals((j \ "name").as[String], "otoroshi-fn-123")
    assertEquals((j \ "image").as[String], "alpine")
    assertEquals((j \ "cpus").as[Int], 2)
    assertEquals((j \ "memoryMb").as[Int], 512)
    assertEquals((j \ "network").as[Boolean], true)
    assertEquals((j \ "networkBackend").as[String], "virtio-net")
    assertEquals((j \ "allowedCidrs").as[Seq[String]], Seq("10.0.0.0/8"))
    assertEquals((j \ "ports" \ 0 \ "host").as[Int], 18080)
    assertEquals((j \ "ports" \ 0 \ "guest").as[Int], 8080)
    assertEquals((j \ "mounts" \ 0 \ "source").as[String], "/host/src")
    assertEquals((j \ "mounts" \ 0 \ "target").as[String], "/app")
    // optional/empty fields must be omitted
    assert((j \ "storageGb").toOption.isEmpty)
  }

  test("SmolMachineSpec.json emits `from` and omits empty `image`") {
    val withImage = SmolMachineSpecV1(name = "m1", image = "alpine").json
    assertEquals((withImage \ "image").as[String], "alpine")
    assert((withImage \ "from").toOption.isEmpty)

    val withFrom = SmolMachineSpecV1(name = "m2", image = "", from = Some("./app.smolmachine")).json
    assertEquals((withFrom \ "from").as[String], "./app.smolmachine")
    assert((withFrom \ "image").toOption.isEmpty, "empty image must be omitted when `from` is used")
  }

  test("ExecRequest.json uses smolvm exec field names (stdin/workdir/timeoutSecs/env)") {
    val req = ExecRequest(
      command = Seq("/app/handler"),
      stdin = Some("""{"hello":"world"}"""),
      env = Seq("K" -> "V"),
      workdir = Some("/app"),
      timeoutSecs = Some(30)
    )
    val j = req.json
    assertEquals((j \ "command").as[Seq[String]], Seq("/app/handler"))
    assertEquals((j \ "stdin").as[String], """{"hello":"world"}""")
    assertEquals((j \ "env" \ 0 \ "name").as[String], "K")
    assertEquals((j \ "env" \ 0 \ "value").as[String], "V")
    assertEquals((j \ "workdir").as[String], "/app")
    assertEquals((j \ "timeoutSecs").as[Long], 30L)
  }

  test("ExecResponse.reads parses the exec result (ignores extra fields)") {
    val json = Json.parse("""{"stdout":"hi","stderr":"","exitCode":0,"durationMs":12}""")
    val r    = ExecResponse.reads(json).get
    assertEquals(r.stdout, "hi")
    assertEquals(r.exitCode, 0)
    assert(r.success)
  }

  test("ExecEnvelope.parseResponse decodes base64 body and headers") {
    val b64  = java.util.Base64.getEncoder.encodeToString("pong".getBytes("UTF-8"))
    val out  = s"""{"status":201,"headers":{"content-type":"text/plain"},"body_base64":"$b64"}"""
    val resp = ExecEnvelope.parseResponse(out).right.get
    assertEquals(resp.status, 201)
    assertEquals(resp.headers("content-type"), "text/plain")
    assertEquals(new String(resp.body, "UTF-8"), "pong")
  }

  test("ExecEnvelope.parseResponse falls back to plain body and reports invalid json") {
    val resp = ExecEnvelope.parseResponse("""{"body":"hello"}""").right.get
    assertEquals(new String(resp.body, "UTF-8"), "hello")
    assertEquals(resp.status, 200)
    assert(ExecEnvelope.parseResponse("not json").isLeft)
  }
}

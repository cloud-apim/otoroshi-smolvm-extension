package com.cloud.apim.otoroshi.extensions.smolvm

import com.cloud.apim.otoroshi.extensions.smolvm.entities.{SmolMachine, SmolMachineSpec, SmolMount, SmolPort}
import com.cloud.apim.otoroshi.extensions.smolvm.runtime.InstanceRecord
import play.api.libs.json.Json

import scala.concurrent.duration._

class EntitiesSpec extends munit.FunSuite {

  test("SmolMachineSpec JSON round-trips (snake_case + ms durations)") {
    val spec = SmolMachineSpec(
      image = "node:22-alpine",
      cpus = 4,
      memoryMb = 1024,
      storageGb = Some(40),
      network = true,
      allowCidrs = Seq("10.0.0.0/8"),
      mounts = Seq(SmolMount("/h", "/g", readonly = true)),
      ports = Seq(SmolPort(18080, 8080)),
      instances = 3,
      mode = "service-via-exec",
      runtime = "node",
      hosts = Seq("http://127.0.0.1:8080"),
      readinessTimeout = 12.seconds,
      launchCommand = Some(Seq("node", "/app/server.js")),
      idleTimeout = 2.minutes
    )
    val parsed = SmolMachineSpec.format.reads(spec.json).get
    assertEquals(parsed, spec)
    assertEquals((spec.json \ "readiness_timeout").as[Long], 12000L)
    assertEquals((spec.json \ "mode").as[String], "service-via-exec")
  }

  test("SmolMachineSpec allows instances=0 (ephemeral), clamps negatives to 0, validates mode/runtime") {
    val s0 = SmolMachineSpec.format.reads(Json.obj("image" -> "alpine", "instances" -> 0, "mode" -> "bogus", "runtime" -> "weird")).get
    assertEquals(s0.instances, 0)
    assertEquals(s0.mode, "service")
    assertEquals(s0.runtime, "none")
    val sNeg = SmolMachineSpec.format.reads(Json.obj("image" -> "alpine", "instances" -> -5)).get
    assertEquals(sNeg.instances, 0)
    val sDefault = SmolMachineSpec.format.reads(Json.obj("image" -> "alpine")).get
    assertEquals(sDefault.instances, 1)
  }

  test("SmolMachineSpec drops blank launch_command / exec_command entries") {
    val j = Json.obj("image" -> "alpine", "launch_command" -> Json.arr(""), "exec_command" -> Json.arr("", "  "))
    val s = SmolMachineSpec.format.reads(j).get
    assertEquals(s.launchCommand, None)
    assertEquals(s.execCommand, None)
  }

  test("SmolMachine entity JSON round-trips with embedded spec") {
    val m = SmolMachine(
      id = "smol-machine_1",
      name = "demo",
      description = "d",
      tags = Seq("a", "b"),
      metadata = Map("k" -> "v"),
      spec = SmolMachineSpec(image = "node:22-alpine", instances = 2)
    )
    val parsed = SmolMachine.format.reads(m.json).get
    assertEquals(parsed.id, "smol-machine_1")
    assertEquals(parsed.name, "demo")
    assertEquals(parsed.tags, Seq("a", "b"))
    assertEquals(parsed.spec.image, "node:22-alpine")
    assertEquals(parsed.spec.instances, 2)
  }

  test("InstanceRecord JSON round-trips") {
    val rec    = InstanceRecord(2, "otoroshi-smol-x-2", "http://h:8080", 20002, "ready", serverLaunched = true, 1L, 2L)
    val parsed = InstanceRecord.parse(Json.stringify(rec.json)).get
    assertEquals(parsed, rec)
  }
}

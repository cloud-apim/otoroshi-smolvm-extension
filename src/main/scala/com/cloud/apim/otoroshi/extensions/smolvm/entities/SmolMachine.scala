package com.cloud.apim.otoroshi.extensions.smolvm.entities

import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * `SmolMachineSpec` is the desired-state description carried by a `SmolMachine` entity.
 * It is a superset of the smolvm create-machine body (image/resources/network/...) plus
 * the v2 orchestration knobs: number of instances, execution mode, node runtime, the
 * machine's own pool of smolvm hosts, readiness, timeouts and idle TTL.
 *
 * Durations are expressed in **milliseconds** in JSON (same convention as the v1 plugin).
 */
case class SmolMachineSpec(
    image: String = "",
    from: Option[String] = None,
    cpus: Int = 2,
    memoryMb: Int = 512,
    storageGb: Option[Int] = None,
    overlayGb: Option[Int] = None,
    gpu: Boolean = false,
    network: Boolean = false,
    allowCidrs: Seq[String] = Seq.empty,
    mounts: Seq[SmolMount] = Seq.empty,
    ports: Seq[SmolPort] = Seq.empty,
    // ---- orchestration -------------------------------------------------------
    instances: Int = 1,                       // 0 = ephemeral (fresh VM per request); n = persistent pool size
    mode: String = "service",                 // "service" | "exec" | "service-via-exec"
    runtime: String = "none",                 // "none" | "node"
    hosts: Seq[String] = Seq.empty,           // per-machine smolvm host pool
    hostsUrl: Option[String] = None,
    hostsRefresh: FiniteDuration = 60.seconds,
    // ---- service / service-via-exec -----------------------------------------
    servicePort: Int = 8080,
    readinessPath: String = "/",
    readinessTimeout: FiniteDuration = 10.seconds,
    launchCommand: Option[Seq[String]] = None, // service-via-exec: command that starts the HTTP server
    // ---- exec ----------------------------------------------------------------
    execCommand: Option[Seq[String]] = None,   // classic watchdog (stdin JSON -> stdout JSON)
    env: Map[String, String] = Map.empty,
    workdir: Option[String] = None,
    // ---- timeouts & lifecycle -----------------------------------------------
    bootTimeout: FiniteDuration = 60.seconds,
    requestTimeout: FiniteDuration = 30.seconds,
    idleTimeout: FiniteDuration = 5.minutes
) {
  def json: JsValue = SmolMachineSpec.format.writes(this)
}

object SmolMachineSpec {

  val default = SmolMachineSpec()

  private val validModes    = Set("service", "exec", "service-via-exec")
  private val validRuntimes  = Set("none", "node")

  val format: Format[SmolMachineSpec] = new Format[SmolMachineSpec] {

    override def writes(o: SmolMachineSpec): JsValue = Json.obj(
      "image"             -> o.image,
      "from"              -> o.from.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "cpus"              -> o.cpus,
      "memory_mb"         -> o.memoryMb,
      "storage_gb"        -> o.storageGb.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
      "overlay_gb"        -> o.overlayGb.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
      "gpu"               -> o.gpu,
      "network"           -> o.network,
      "allow_cidrs"       -> o.allowCidrs,
      "mounts"            -> JsArray(o.mounts.map(_.json)),
      "ports"             -> JsArray(o.ports.map(_.json)),
      "instances"         -> o.instances,
      "mode"              -> o.mode,
      "runtime"           -> o.runtime,
      "hosts"             -> o.hosts,
      "hosts_url"         -> o.hostsUrl.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "hosts_refresh"     -> o.hostsRefresh.toMillis,
      "service_port"      -> o.servicePort,
      "readiness_path"    -> o.readinessPath,
      "readiness_timeout" -> o.readinessTimeout.toMillis,
      "launch_command"    -> o.launchCommand.map(s => Json.toJson(s)).getOrElse(JsNull).as[JsValue],
      "exec_command"      -> o.execCommand.map(s => Json.toJson(s)).getOrElse(JsNull).as[JsValue],
      "env"               -> o.env,
      "workdir"           -> o.workdir.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "boot_timeout"      -> o.bootTimeout.toMillis,
      "request_timeout"   -> o.requestTimeout.toMillis,
      "idle_timeout"      -> o.idleTimeout.toMillis
    )

    override def reads(json: JsValue): JsResult[SmolMachineSpec] = Try {
      def ms(key: String, default: FiniteDuration): FiniteDuration =
        (json \ key).asOpt[Long].map(_.milliseconds).getOrElse(default)
      def parseMount(o: JsValue): Option[SmolMount] =
        (o \ "source").asOpt[String].flatMap(s => (o \ "target").asOpt[String].map { t =>
          SmolMount(s, t, (o \ "readonly").asOpt[Boolean].getOrElse(false))
        })
      def parsePort(o: JsValue): Option[SmolPort] =
        (o \ "host").asOpt[Int].flatMap(h => (o \ "guest").asOpt[Int].map(g => SmolPort(h, g)))
      val rawMode    = (json \ "mode").asOpt[String].map(_.trim.toLowerCase).getOrElse("service")
      val mode       = if (validModes.contains(rawMode)) rawMode else "service"
      val rawRuntime = (json \ "runtime").asOpt[String].map(_.trim.toLowerCase).getOrElse("none")
      val runtime    = if (validRuntimes.contains(rawRuntime)) rawRuntime else "none"
      SmolMachineSpec(
        image = (json \ "image").asOpt[String].getOrElse(""),
        from = (json \ "from").asOpt[String].filter(_.trim.nonEmpty),
        cpus = (json \ "cpus").asOpt[Int].getOrElse(2),
        memoryMb = (json \ "memory_mb").asOpt[Int].getOrElse(512),
        storageGb = (json \ "storage_gb").asOpt[Int],
        overlayGb = (json \ "overlay_gb").asOpt[Int],
        gpu = (json \ "gpu").asOpt[Boolean].getOrElse(false),
        network = (json \ "network").asOpt[Boolean].getOrElse(false),
        allowCidrs = (json \ "allow_cidrs").asOpt[Seq[String]].getOrElse(Seq.empty),
        mounts = (json \ "mounts").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(parseMount),
        ports = (json \ "ports").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(parsePort),
        instances = math.max(0, (json \ "instances").asOpt[Int].getOrElse(1)), // 0 = ephemeral (VM per request)
        mode = mode,
        runtime = runtime,
        hosts = (json \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty),
        hostsUrl = (json \ "hosts_url").asOpt[String].filter(_.trim.nonEmpty),
        hostsRefresh = ms("hosts_refresh", 60.seconds),
        servicePort = (json \ "service_port").asOpt[Int].getOrElse(8080),
        readinessPath = (json \ "readiness_path").asOpt[String].getOrElse("/"),
        readinessTimeout = ms("readiness_timeout", 10.seconds),
        launchCommand = (json \ "launch_command").asOpt[Seq[String]].map(_.filter(_.trim.nonEmpty)).filter(_.nonEmpty),
        execCommand = (json \ "exec_command").asOpt[Seq[String]].map(_.filter(_.trim.nonEmpty)).filter(_.nonEmpty),
        env = (json \ "env").asOpt[Map[String, String]].getOrElse(Map.empty),
        workdir = (json \ "workdir").asOpt[String].filter(_.trim.nonEmpty),
        bootTimeout = ms("boot_timeout", 60.seconds),
        requestTimeout = ms("request_timeout", 30.seconds),
        idleTimeout = ms("idle_timeout", 5.minutes)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

/**
 * `SmolMachine` is the Otoroshi entity (managed by the SmolMachine admin extension). It is
 * stored in otoroshi's own datastore and synced across the cluster like any other entity.
 * The runtime placement (instance -> host) lives in a separate external state (see the
 * extension), not here.
 */
case class SmolMachine(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    spec: SmolMachineSpec = SmolMachineSpec.default
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = SmolMachine.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object SmolMachine {

  val format: Format[SmolMachine] = new Format[SmolMachine] {
    override def writes(o: SmolMachine): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "enabled"     -> o.enabled,
      "spec"        -> o.spec.json
    )
    override def reads(json: JsValue): JsResult[SmolMachine] = Try {
      SmolMachine(
        location = EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        spec = (json \ "spec").asOpt(SmolMachineSpec.format).getOrElse(SmolMachineSpec.default)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }

  def template(): SmolMachine = SmolMachine(
    id = s"smol-machine_${java.util.UUID.randomUUID().toString}",
    name = "New smol machine",
    description = "A new smolvm machine",
    spec = SmolMachineSpec(
      image = "node:22-alpine",
      instances = 1,
      mode = "service-via-exec",
      runtime = "node",
      network = true,
      hosts = Seq("http://127.0.0.1:8080"),
      launchCommand = Some(Seq("node", "/app/server.js"))
    )
  )

  def resource(env: Env, datastores: otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.SmolMachineDatastores, states: otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.SmolMachineState): Resource = {
    Resource(
      "SmolMachine",
      "smol-machines",
      "smol-machine",
      "smolvm.extensions.cloud-apim.com",
      ResourceVersion("v1", served = true, deprecated = false, storage = true),
      GenericResourceAccessApiWithState[SmolMachine](
        SmolMachine.format,
        classOf[SmolMachine],
        id => datastores.smolMachinesDatastore.key(id),
        c => datastores.smolMachinesDatastore.extractId(c),
        json => json.select("id").asString,
        () => "id",
        tmpl = (_, _, _) => SmolMachine.template().json,
        stateAll = () => states.allSmolMachines(),
        stateOne = id => states.smolMachine(id),
        stateUpdate = values => states.updateSmolMachines(values)
      )
    )
  }
}

trait SmolMachineDataStore extends BasicStore[SmolMachine]

class KvSmolMachineDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends SmolMachineDataStore
    with RedisLikeStore[SmolMachine] {
  override def fmt: Format[SmolMachine]                = SmolMachine.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:smolmachines:$id"
  override def extractId(value: SmolMachine): String   = value.id
}

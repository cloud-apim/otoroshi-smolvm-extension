package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm

import akka.actor.Cancellable
import com.cloud.apim.otoroshi.extensions.smolvm.entities.{KvSmolMachineDataStore, SmolMachine, SmolMachineDataStore}
import com.cloud.apim.otoroshi.extensions.smolvm.runtime.{SmolMachineManager, SmolStateBackend}
import otoroshi.cluster.ClusterMode
import otoroshi.env.Env
import otoroshi.models.EntityLocationSupport
import otoroshi.next.extensions._
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.mvc.Results

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration._

class SmolMachineDatastores(env: Env, extensionId: AdminExtensionId) {
  val smolMachinesDatastore: SmolMachineDataStore = new KvSmolMachineDataStore(extensionId, env.datastores.redis, env)
}

class SmolMachineState(env: Env) {

  private val machines = new UnboundedTrieMap[String, SmolMachine]()

  def smolMachine(id: String): Option[SmolMachine] = machines.get(id)
  def allSmolMachines(): Seq[SmolMachine]          = machines.values.toSeq

  def updateSmolMachines(values: Seq[SmolMachine]): Unit = {
    machines.addAll(values.map(v => (v.id, v))).remAll(machines.keySet.toSeq.diff(values.map(_.id)))
  }
}

object SmolMachineExtension {
  val logger = Logger("cloud-apim-smolmachine")
  val id     = AdminExtensionId("cloud-apim.extensions.SmolMachine")
}

class SmolMachineExtension(val env: Env) extends AdminExtension {

  private lazy val datastores   = new SmolMachineDatastores(env, id)
  private lazy val states       = new SmolMachineState(env)
  private lazy val stateBackend = SmolStateBackend(env, "smolvm-state", configuration.getOptional[String]("state.uri"))

  lazy val manager = new SmolMachineManager(env, stateBackend)

  private val reaperRef = new AtomicReference[Cancellable]()

  override def id: AdminExtensionId        = SmolMachineExtension.id
  override def name: String                = "SmolVM Machines"
  override def description: Option[String] =
    "Manage smolvm micro-VM machines as otoroshi entities (service / exec / service-via-exec, node runtime)".some
  override def enabled: Boolean            = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  def smolMachine(id: String): Option[SmolMachine] = states.smolMachine(id)

  override def start(): Unit = {
    SmolMachineExtension.logger.info("the 'SmolVM Machines' extension is enabled !")
    com.cloud.apim.otoroshi.extensions.smolvm.workflows.SmolMachineWorkflowFunctions.registerAll()
    val reaperEnabled = configuration.getOptional[Boolean]("reaper.enabled").getOrElse(true)
    if (reaperEnabled) {
      implicit val ec = env.otoroshiExecutionContext
      val interval    = configuration.getOptional[Long]("reaper.interval-ms").getOrElse(30000L).millis
      val cancellable = env.otoroshiActorSystem.scheduler.scheduleWithFixedDelay(interval, interval)(new Runnable {
        override def run(): Unit = {
          val isLeader = env.clusterConfig.mode == ClusterMode.Off || env.clusterConfig.mode.isLeader
          if (isLeader) {
            states.allSmolMachines().foreach(m => manager.reap(m))
          }
        }
      })(ec)
      reaperRef.set(cancellable)
      SmolMachineExtension.logger.info(s"[smolmachine] idle/orphan reaper scheduled every $interval (leader only)")
    }
  }

  override def stop(): Unit = {
    Option(reaperRef.get()).foreach(_.cancel())
  }

  override def syncStates(): Future[Unit] = {
    implicit val ec  = env.otoroshiExecutionContext
    implicit val ev  = env
    for {
      machines <- datastores.smolMachinesDatastore.findAll()
    } yield {
      states.updateSmolMachines(machines)
      ()
    }
  }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = Seq(
    AdminExtensionEntity(SmolMachine.resource(env, datastores, states))
  )

  private def getResourceCode(path: String): String = {
    Option(getClass.getClassLoader.getResourceAsStream(path)).map { is =>
      try scala.io.Source.fromInputStream(is, "UTF-8").mkString
      finally is.close()
    }.getOrElse(s"console.error('[smolmachine] resource $path not found');")
  }

  private lazy val extensionJs = getResourceCode("cloudapim/extensions/smolvm/extension.js")

  override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = Seq(
    AdminExtensionFrontendExtension("/extensions/assets/cloud-apim/extensions/smolvm/extension.js")
  )

  override def assets(): Seq[AdminExtensionAssetRoute] = Seq(
    AdminExtensionAssetRoute(
      "/extensions/assets/cloud-apim/extensions/smolvm/extension.js",
      (_, _) => Results.Ok(extensionJs).as("application/javascript").vfuture
    )
  )
}

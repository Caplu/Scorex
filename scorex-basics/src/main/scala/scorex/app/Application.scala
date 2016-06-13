package scorex.app

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import scorex.api.http.{ApiRoute, CompositeHttpService}
import scorex.block.Block
import scorex.consensus.ConsensusModule
import scorex.consensus.mining.BlockGeneratorController
import scorex.network._
import scorex.network.message.{BasicMessagesRepo, MessageHandler, MessageSpec}
import scorex.network.peer.PeerManager
import scorex.settings.Settings
import scorex.transaction.state.SecretHolderGenerator
import scorex.transaction.{History, TransactionModule}
import scorex.utils.ScorexLogging
import scorex.wallet.Wallet

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.runtime.universe.Type

trait Application extends ScorexLogging {
  val ApplicationNameLimit = 50

  val applicationName: String

  //redefine it as lazy val
  def appVersion: ApplicationVersion

  //settings
  implicit val settings: Settings

  type TM <: TransactionModule
  type CM <: ConsensusModule[TM]

  type Prop = TM#P
  type SH = TM#SH
  type TX = TM#TX

  //modules
  implicit val consensusModule: CM
  implicit val transactionModule: TM

  implicit val generator: SecretHolderGenerator[SH]

  //api
  val apiRoutes: Seq[ApiRoute]
  val apiTypes: Seq[Type]

  protected implicit lazy val actorSystem = ActorSystem("lagonaki")

  protected val additionalMessageSpecs: Seq[MessageSpec[_]]

  lazy val basicMessagesSpecsRepo = new BasicMessagesRepo()

  //p2p
  lazy val upnp = new UPnP(settings)
  if (settings.upnpEnabled) upnp.addPort(settings.port)

  lazy val messagesHandler: MessageHandler = MessageHandler(basicMessagesSpecsRepo.specs ++ additionalMessageSpecs)

  lazy val peerManager = actorSystem.actorOf(Props(classOf[PeerManager], this))

  lazy val networkController = actorSystem.actorOf(Props(classOf[NetworkController], this), "networkController")
  lazy val blockGenerator = actorSystem.actorOf(Props(classOf[BlockGeneratorController], this), "blockGenerator")

  //interface to append log and state
  lazy val blockStorage = transactionModule.blockStorage

  lazy val history: History = blockStorage.history

  lazy val historySynchronizer = actorSystem.actorOf(Props(classOf[HistorySynchronizer[TX]], this), "HistorySynchronizer")
  lazy val historyReplier = actorSystem.actorOf(Props(classOf[HistoryReplier], this), "HistoryReplier")


  implicit val materializer = ActorMaterializer()
  val combinedRoute = CompositeHttpService(actorSystem, apiTypes, apiRoutes, settings).compositeRoute


  def run() {
    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")

    checkGenesis()

    Http().bindAndHandle(combinedRoute, "0.0.0.0", settings.rpcPort)

    historySynchronizer ! Unit
    historyReplier ! Unit
    actorSystem.actorOf(Props(classOf[PeerSynchronizer], this), "PeerSynchronizer")

    //on unexpected shutdown
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        log.error("Unexpected shutdown")
        stopAll()
      }
    })
  }

  def stopAll(): Unit = synchronized {
    log.info("Stopping network services")
    if (settings.upnpEnabled) upnp.deletePort(settings.port)
    networkController ! NetworkController.ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")
    actorSystem.terminate().onComplete { _ =>
      log.info("Closing wallet")
      transactionModule.stop()

      log.info("Exiting from the app...")
      System.exit(0)
    }
  }

  def checkGenesis(): Unit = {
    if (transactionModule.blockStorage.history.isEmpty) {
      val genesisBlock = Block.genesis(settings.genesisTimestamp)
      transactionModule.blockStorage.appendBlock(genesisBlock)
      log.info("Genesis block has been added to the state")
    }
  }.ensuring(transactionModule.blockStorage.history.height() >= 1)
}

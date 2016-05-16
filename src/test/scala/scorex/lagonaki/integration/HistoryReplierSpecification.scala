package scorex.lagonaki.integration

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{Matchers, WordSpecLike}
import scorex.lagonaki.TestingCommons
import scorex.network.HistorySynchronizer
import scorex.transaction.AccountTransaction

class HistoryReplierSpecification(_system: ActorSystem)
  extends TestKit(_system)
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with TestingCommons {

  def this() = this(ActorSystem("HistoryReplierSpecification"))

  val probe = new TestProbe(system)

  val hs = system.actorOf(Props(classOf[HistorySynchronizer[AccountTransaction]], application))

  lazy val application = TestingCommons.application

  //todo: get tests done
  "HistoryReplier actor" must {
    "return block for GetBlock" in {

    }

    "return sigs for GetSignaturesMessage" in {

    }
  }
}

package scorex.lagonaki.integration

import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}
import scorex.transaction.account.{AccountTransaction, BalanceSheet, PublicKeyAccount}
import scorex.consensus.mining.BlockGeneratorController._
import scorex.lagonaki.{TestingCommons, TransactionTestingCommons}
import scorex.transaction.state.database.UnconfirmedTransactionsDatabaseImpl
import scorex.utils.{ScorexLogging, untilTimeout}

import scala.concurrent.duration._

class ValidChainGenerationSpecification extends FunSuite with Matchers with BeforeAndAfterAll with ScorexLogging
with TransactionTestingCommons {

  import TestingCommons._

  val peers = applications.tail
  val app = peers.head
  val state = app.transactionModule.blockStorage.state
  val history = app.transactionModule.blockStorage.history

  def waitGenerationOfBlocks(howMany: Int): Unit = {
    val height = maxHeight()
    untilTimeout(5.minutes, 10.seconds) {
      peers.foreach(_.blockStorage.history.height() should be >= height + howMany)
    }
  }

  def maxHeight(): Int = peers.map(_.blockStorage.history.height()).max

  def cleanTransactionPool(): Unit = {
    UnconfirmedTransactionsDatabaseImpl.all().foreach(tx => UnconfirmedTransactionsDatabaseImpl.remove(tx))
    UnconfirmedTransactionsDatabaseImpl.all().size shouldBe 0
  }

  test("generate 3 blocks and synchronize") {
    val genBal = peers.flatMap(a => a.wallet.privateKeyAccounts()).map(app.blockStorage.state.generationBalance(_)).sum
    genBal should be >= (peers.head.transactionModule.InitialBalance / 2)
    genValidTransaction()

    waitGenerationOfBlocks(3)

    val last = peers.head.blockStorage.history.lastBlock
    untilTimeout(5.minutes, 10.seconds) {
      peers.head.blockStorage.history.contains(last) shouldBe true
    }
  }

  test("Don't include same transactions twice") {
    val last = history.lastBlock
    val h = history.heightOf(last).get
    val incl = includedTransactions(last, history)
    require(incl.nonEmpty)
    waitGenerationOfBlocks(0) // all peer should contain common block
    peers.foreach { p =>
      incl foreach { tx =>
        p.blockStorage.state.included(tx).isDefined shouldBe true
        p.blockStorage.state.included(tx).get should be <= h
      }
    }

    cleanTransactionPool()

    incl.foreach(tx => UnconfirmedTransactionsDatabaseImpl.putIfNew(tx.asInstanceOf[AccountTransaction]))
    UnconfirmedTransactionsDatabaseImpl.all().size shouldBe incl.size
    val tx = genValidTransaction(randomAmnt = false)
    UnconfirmedTransactionsDatabaseImpl.all().size shouldBe incl.size + 1

    waitGenerationOfBlocks(2)

    peers.foreach { p =>
      incl foreach { tx =>
        p.blockStorage.state.included(tx).isDefined shouldBe true
        p.blockStorage.state.included(tx).get should be <= h
      }
    }
  }

  test("Double spending") {
    cleanTransactionPool()
    val recepient = new PublicKeyAccount(Array.empty)
    val trans = accounts.flatMap { a =>
      val senderBalance = state.asInstanceOf[BalanceSheet].balance(a.address)
      (1 to 2) map (i => transactionModule.createPayment(a, recepient, senderBalance / 2, 1))
    }
    val valid = transactionModule.packUnconfirmed()
    valid.nonEmpty shouldBe true
    state.validate(trans).nonEmpty shouldBe true
    valid.size should be < trans.size

    waitGenerationOfBlocks(2)

    accounts.foreach(a => state.asInstanceOf[BalanceSheet].balance(a.address) should be >= 0L)
    trans.exists(tx => state.included(tx).isDefined) shouldBe true // Some of transactions should be included in state
    trans.forall(tx => state.included(tx).isDefined) shouldBe false // But some should not
  }

  test("Rollback state") {
    val last = history.lastBlock
    val st1 = state.hash
    val height = history.heightOf(last).get

    //Wait for nonEmpty block
    untilTimeout(1.minute, 1.second) {
      genValidTransaction()
      peers.foreach(_.blockStorage.history.height() should be > height)
      history.height() should be > height
      state.hash should not be st1
      peers.foreach(_.transactionModule.blockStorage.history.contains(last))
    }
    waitGenerationOfBlocks(0)

    untilTimeout(10.seconds) {
      peers.foreach { p =>
        p.blockGenerator ! StopGeneration
        p.transactionModule.blockStorage.removeAfter(last.uniqueId)
        p.history.lastBlock.encodedId shouldBe last.encodedId
      }
    }
    peers.foreach(_.blockGenerator ! StartGeneration)

    state.hash shouldBe st1
  }

}
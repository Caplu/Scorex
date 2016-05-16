package scorex.lagonaki

import scorex.block.Block
import scorex.lagonaki.TestingCommons._
import scorex.transaction.account.BalanceSheet
import scorex.transaction.{AccountTransaction, History, GenesisTransaction}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

trait TransactionTestingCommons {
  implicit val consensusModule = application.consensusModule
  implicit val transactionModule = application.transactionModule
  if (transactionModule.blockStorage.history.isEmpty) {
    transactionModule.blockStorage.appendBlock(Block.genesis())
  }
  val wallet = application.wallet
  if (wallet.privateKeyAccounts().isEmpty) {
    wallet.generateNewAccounts(3)
  }
  val accounts = wallet.privateKeyAccounts()
    .filter(a => transactionModule.blockStorage.state.asInstanceOf[BalanceSheet].generationBalance(a) > 0)

  val ab = accounts.map(a => transactionModule.blockStorage.state.asInstanceOf[BalanceSheet].generationBalance(a)).sum
  require(ab > 2)

  def genValidBlock(): Block[AccountTransaction] = {
    Await.result(consensusModule.generateNextBlocks(accounts)(transactionModule), 10.seconds).headOption match {
      case Some(block: Block[AccountTransaction]) if block.isValid => block
      case None =>
        Thread.sleep(500)
        genValidBlock()
    }
  }

  val genesisAccs = application.blockStorage.history.genesis.transactions.flatMap(_ match {
    case gtx: GenesisTransaction => Some(gtx.recipient)
    case _ => None
  })

  def genValidTransaction(randomAmnt: Boolean = true): AccountTransaction = {
    val senderAcc = accounts(Random.nextInt(accounts.size))
    val senderBalance = transactionModule.blockStorage.state.asInstanceOf[BalanceSheet].generationBalance(senderAcc)
    val fee = Random.nextInt(5).toLong + 1
    if (senderBalance <= fee) {
      genValidTransaction(randomAmnt)
    } else {
      val amt = if (randomAmnt) Math.abs(Random.nextLong() % (senderBalance - fee))
      else senderBalance - fee
      val tx = transactionModule.createPayment(senderAcc, accounts(Random.nextInt(accounts.size)), amt, fee)
      if (transactionModule.blockStorage.state.isValid(tx)) tx
      else genValidTransaction(randomAmnt)
    }
  }

  def includedTransactions(b: Block[AccountTransaction], history: History[AccountTransaction]): Seq[AccountTransaction] = {
    if (b.transactions.isEmpty) includedTransactions(history.parent(b).get, history)
    else b.transactions
  }

}

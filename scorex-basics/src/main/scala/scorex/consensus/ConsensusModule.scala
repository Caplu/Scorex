package scorex.consensus

import scorex.block.{Block, BlockProcessingModule}
import scorex.transaction.account.{Account, PrivateKeyAccount}
import scorex.transaction.state.StateElement
import scorex.transaction.{Transaction, TransactionModule}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


trait ConsensusModule[ConsensusBlockData, SE <: StateElement, TX <: Transaction[SE]] extends BlockProcessingModule[ConsensusBlockData] {

  def isValid[TransactionalBlockData](block: Block[TX])(implicit transactionModule: TransactionModule[TransactionalBlockData, TX]): Boolean

  /**
    * Fees could go to a single miner(forger) usually, but can go to many parties, e.g. see
    * Meni Rosenfeld's Proof-of-Activity proposal http://eprint.iacr.org/2014/452.pdf
    */
  def feesDistribution(block: Block[TX]): Map[SE, Long]

  /**
    * Get block producers(miners/forgers). Usually one miner produces a block, but in some proposals not
    * (see e.g. Meni Rosenfeld's Proof-of-Activity paper http://eprint.iacr.org/2014/452.pdf)
    * @param block
    * @return
    */
  def generators(block: Block[TX]): Seq[SE]

  def blockScore(block: Block[TX])(implicit transactionModule: TransactionModule[_, TX]): BigInt

  def generateNextBlock[TransactionalBlockData](account: PrivateKeyAccount)
                                               (implicit transactionModule: TransactionModule[TransactionalBlockData, TX]): Future[Option[Block[TX]]]

  def generateNextBlocks[TransactionalBlockData](accounts: Seq[PrivateKeyAccount])
                                                (implicit transactionModule: TransactionModule[TransactionalBlockData, TX]): Future[Seq[Block[TX]]] = {
    Future.sequence(accounts.map(acc => generateNextBlock(acc))).map(_.flatten)
  }

  def consensusBlockData(block: Block[TX]): ConsensusBlockData
}

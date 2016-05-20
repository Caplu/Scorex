package scorex.consensus

import scorex.transaction.AccountTransaction
import scorex.transaction.account.Account
import scorex.block.Block

/**
  * Data and functions related to a consensus algo
  */

trait LagonakiConsensusModule[ConsensusBlockData, TX <: AccountTransaction] extends ConsensusModule[ConsensusBlockData, Account, TX] {

  /**
    * In Lagonaki, for both consensus modules, there's only one block generator
    * @param block - block to extract fees distribution from
    * @return
    */
  override def feesDistribution(block: Block[TX]): Map[Account, Long] = {
    //todo: asInstanceOf, without exception catch!!!
    val cm = block.consensusModule.asInstanceOf[ConsensusModule[block.ConsensusDataType, Account, TX]]
    val forger = cm.producers(block).ensuring(_.size == 1).head
    val fee = block.transactions.map(_.fee).sum
    Map(forger -> fee)
  }

  override def producers(block: Block[TX]): Seq[Account]
}

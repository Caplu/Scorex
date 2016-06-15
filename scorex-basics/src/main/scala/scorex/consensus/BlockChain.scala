package scorex.consensus

import scorex.block.Block
import scorex.block.Block.BlockId
import scorex.transaction.box.Proposition
import scorex.utils.ScorexLogging

trait BlockChain extends History with ScorexLogging {

  def blockAt(height: Int): Option[Block]

  def genesisBlock: Option[Block] = blockAt(1)

  override def parent(block: Block, back: Int = 1): Option[Block] = {
    require(back > 0)
    heightOf(block.parentId).flatMap(referenceHeight => blockAt(referenceHeight - back + 1))
  }

  private[consensus] def discardBlock(): BlockChain

  override def lastBlocks(howMany: Int): Seq[Block] =
    (Math.max(1, height() - howMany + 1) to height()).flatMap(blockAt).reverse

  def lookForward(parentSignature: BlockId, howMany: Int): Seq[BlockId] =
    heightOf(parentSignature).map { h =>
      (h + 1).to(Math.min(height(), h + howMany: Int)).flatMap(blockAt).map(_.id)
    }.getOrElse(Seq())

  def children(block: Block): Seq[Block]

  override lazy val genesis: Block = blockAt(1).get
}
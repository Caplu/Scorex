package scorex.transaction.state.database.blockchain

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scorex.block.Block
import scorex.lagonaki.BlockTestingCommons
import scorex.transaction.AccountTransaction
import scorex.utils._

class BlockTreeSpecification extends PropSpec with PropertyChecks
with GeneratorDrivenPropertyChecks with Matchers with BlockTestingCommons {

  testTree(new StoredBlockTree(None, 100), "Memory")

  def testTree(blockTree: StoredBlockTree[AccountTransaction], prefix: String): Unit = {

    val blockGen: Gen[Block[AccountTransaction]] = for {
      gb <- Arbitrary.arbitrary[Long]
      gs <- Arbitrary.arbitrary[Array[Byte]]
      seed <- Arbitrary.arbitrary[Array[Byte]]
    } yield genBlock(gb, gs, seed)

    property(s"$prefix: Add genesis") {
      blockTree.height() shouldBe 0
      blockTree.appendBlock(genesis).isSuccess shouldBe true
      blockTree.height() shouldBe 1
      blockTree.blockById(genesis.uniqueId) should not be None
    }

    property(s"$prefix: Add linear blocks in chain") {
      blockTree.height() shouldBe 1
      lastBlockId = blockTree.lastBlock.uniqueId

      forAll(blockGen) { (block: Block[AccountTransaction]) =>
        val prevH = blockTree.height()
        val prevS = blockTree.score()
        val prevB = blockTree.lastBlock

        blockTree.appendBlock(block).isSuccess shouldBe true

        blockTree.height() shouldBe prevH + 1
        blockTree.score() shouldBe prevS + consensusModule.blockScore(block)
        blockTree.lastBlock.uniqueId should contain theSameElementsAs block.uniqueId
        blockTree.parent(block).get.uniqueId should contain theSameElementsAs prevB.uniqueId
        blockTree.contains(block) shouldBe true
        blockTree.contains(prevB.uniqueId) shouldBe true
      }
    }

    property(s"$prefix: Add non-linear blocks in tree") {
      val branchPoint = blockTree.lastBlock

      //Add block to best chain
      val block = genBlock(20, randomBytes(), randomBytes(), Some(branchPoint.uniqueId))
      blockTree.appendBlock(block).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs block.uniqueId

      //Add block with lower score to branch point
      val branchedBlock = genBlock(21, randomBytes(), randomBytes(), Some(branchPoint.uniqueId))
      blockTree.appendBlock(branchedBlock).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs block.uniqueId

      //Add block with the better score to branch point
      val bestBlock = genBlock(19, randomBytes(), randomBytes(), Some(branchPoint.uniqueId))
      blockTree.appendBlock(bestBlock).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs bestBlock.uniqueId

      //Add block to subtree with smaller score to make it best subtree
      val longerTreeBlock = genBlock(19, randomBytes(), randomBytes(), Some(branchedBlock.uniqueId))
      blockTree.appendBlock(longerTreeBlock).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs longerTreeBlock.uniqueId
    }

    property(s"$prefix: Wrong block") {
      val prevS = blockTree.score()
      val prevB = blockTree.lastBlock
      val wrongBlock = genBlock(19, randomBytes(), randomBytes(), Some(randomBytes(51)))

      //Block with no parent in blockTree
      blockTree.appendBlock(wrongBlock).isSuccess shouldBe false
      blockTree.score() shouldBe prevS
      blockTree.lastBlock.uniqueId should contain theSameElementsAs prevB.uniqueId

      //Apply same block twice
      blockTree.appendBlock(prevB).isSuccess shouldBe true
      blockTree.score() shouldBe prevS
      blockTree.lastBlock.uniqueId should contain theSameElementsAs prevB.uniqueId
    }

    property(s"$prefix: Look forward") {
      blockTree.height() should be > 1
      forAll(Gen.choose(1, 100)) { (limit: Int) =>
        val newBlocks = blockTree.lookForward(genesis.uniqueId, limit)
        newBlocks.size shouldBe Math.min(limit, blockTree.height() - 1)
      }
    }
  }
}
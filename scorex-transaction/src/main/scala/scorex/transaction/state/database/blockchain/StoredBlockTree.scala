package scorex.transaction.state.database.blockchain

import java.io.File

import org.mapdb.{DB, DBMaker, HTreeMap, Serializer}
import scorex.block.Block
import scorex.block.Block.BlockId
import scorex.consensus.ConsensusModule
import scorex.crypto.encode.Base58
import scorex.transaction.BlockStorage._
import scorex.transaction.account.Account
import scorex.transaction.{Transaction, BlockTree, TransactionModule}
import scorex.utils.ScorexLogging

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
  * TODO fix
  * If no datafolder provided, blocktree lives in RAM (useful for tests)
  */
class StoredBlockTree[TX <: Transaction](dataFolderOpt: Option[String], MaxRollback: Int)
                     (implicit consensusModule: ConsensusModule[_, TX],
                      transactionModule: TransactionModule[_, TX])
  extends BlockTree[TX] with ScorexLogging {

  trait BlockTreePersistence {
    type Score = BigInt
    type Height = Int
    type StoredBlock = (Block[TX], Score, Height)

    def writeBlock(block: Block[TX]): Try[Boolean]

    def readBlock(id: BlockId): Option[StoredBlock]

    def readBlock(block: Block[TX]): Option[StoredBlock] = readBlock(block.id)

    def filter(f: Block[TX] => Boolean): Seq[StoredBlock] = {
      @tailrec
      def iterate(b: StoredBlock, f: Block[TX] => Boolean, acc: Seq[StoredBlock] = Seq.empty): Seq[StoredBlock] = {
        val newAcc: Seq[StoredBlock] = if (f(b._1)) b +: acc else acc
        readBlock(b._1.parentId) match {
          case Some(parent) => iterate(parent, f, newAcc)
          case None => newAcc
        }
      }
      iterate(readBlock(getBestBlockId).get, f)
    }

    def exists(block: Block[TX]): Boolean = exists(block.id)

    def exists(blockId: BlockId): Boolean = readBlock(blockId).isDefined

    def bestBlock: Option[StoredBlock] = readBlock(getBestBlockId)

    protected def getBestBlockId: BlockId

    def changeBestChain(changes: Seq[(Block[TX], Direction)]): Try[Unit]

    def lookForward(parentSignature: BlockId, howMany: Int): Seq[BlockId]

  }

  //TODO remove old blocks
  class MapDBBlockTreePersistence(db: DB) extends BlockTreePersistence {
    type MapDBStoredBlock = (Array[Byte], Score, Height)

    private lazy val map: HTreeMap[BlockId, MapDBStoredBlock] = db.hashMapCreate("blocks")
      .keySerializer(Serializer.BYTE_ARRAY).makeOrGet()

    private lazy val bestBlockStorage: HTreeMap[Int, BlockId] = db.hashMapCreate("bestBlock")
      .keySerializer(Serializer.INTEGER).valueSerializer(Serializer.BYTE_ARRAY).makeOrGet()

    private lazy val bestChainStorage: HTreeMap[BlockId, (BlockId, Option[BlockId])] = db.hashMapCreate("bestChain")
      .keySerializer(Serializer.BYTE_ARRAY).makeOrGet()

    private var bestBlockId: BlockId = Option(bestBlockStorage.get(0)).getOrElse(Array.empty)

    override protected def getBestBlockId: BlockId = bestBlockId

    private def setBestBlockId(newId: BlockId) = {
      bestBlockId = newId
      bestBlockStorage.put(0, newId)
      db.commit()
    }

    override def lookForward(parentSignature: BlockId, howMany: Height): Seq[BlockId] = Try {
      def loop(parentSignature: BlockId, howMany: Height, acc: Seq[BlockId]): Seq[BlockId] = howMany match {
        case 0 => acc
        case _ =>
          Option(bestChainStorage.get(parentSignature)) match {
            case Some(block) =>
              block._2 match {
                case Some(blockId) => loop(blockId, howMany - 1, blockId +: acc)
                case None => acc
              }
            case None =>
              log.error(s"Failed to get block ${parentSignature.mkString} from best chain storage")
              acc
          }
      }

      loop(parentSignature, howMany, Seq.empty).reverse
    }.recoverWith { case t: Throwable =>
      log.error("Error when getting blocks", t)
      t.printStackTrace()
      Try(Seq.empty)
    }.getOrElse(Seq.empty)

    override def changeBestChain(changes: Seq[(Block[TX], Direction)]): Try[Unit] = Try {
      changes.map { c =>
        val parentId = c._1.parentId
        c._2 match {
          case Forward =>
            bestChainStorage.put(c._1.id, (parentId, None))
            val prev = bestChainStorage.get(parentId)
            bestChainStorage.put(parentId, (prev._1, Some(c._1.id)))
          case Reversed =>
            bestChainStorage.remove(c._1.id)
            val prev = bestChainStorage.get(parentId)
            bestChainStorage.put(parentId, (prev._1, None))
        }
      }
    }

    /**
      *
      * @return true when best block added, false when block score is less then current score
      */
    override def writeBlock(block: Block[TX]): Try[Boolean] = Try {
      if (exists(block)) log.warn(s"Trying to add block ${block.encodedId} that is already in tree "
        + s" at height ${readBlock(block).map(_._3)}")
      val parent = readBlock(block.parentId)
      lazy val blockScore = consensusModule.blockScore(block).ensuring(_ > 0)
      parent match {
        case Some(p) =>
          if (height() - p._3 > MaxRollback) {
            throw new Error(s"Trying to add block with too old parent")
          } else {
            val s = p._2 + blockScore
            map.put(block.id, (block.bytes, s, p._3 + 1))
            db.commit()
            if (s >= score()) {
              setBestBlockId(block.id)
              true
            } else false
          }
        case None => map.isEmpty match {
          case true =>
            setBestBlockId(block.id)
            map.put(block.id, (block.bytes, blockScore, 1))
            db.commit()
            true
          case false =>
            throw new Error(s"Parent ${block.parentId.mkString} block is not in tree")
        }
      }
    }

    override def exists(blockId: BlockId): Boolean = map.containsKey(blockId)

    override def readBlock(key: BlockId): Option[StoredBlock] = Try {
      val stored = map.get(key)
      (Block.parseBytes(stored._1).get, stored._2, stored._3)
    } match {
      case Success(v) =>
        Some(v)
      case Failure(e) =>
        log.debug("Enable readBlock for key: " + Base58.encode(key))
        None
    }
  }

  private val blockStorage: BlockTreePersistence = dataFolderOpt match {
    case Some(dataFolder) =>
      new File(dataFolder).mkdirs()
      val file = new File(dataFolder + "blocktree.mapDB")
      val db = DBMaker.appendFileDB(file).fileMmapEnableIfSupported().closeOnJvmShutdown().checksumEnable().make()
      new MapDBBlockTreePersistence(db)
    case _ => new MapDBBlockTreePersistence(DBMaker.memoryDB().make())
  }

  override def height(): Int = blockStorage.bestBlock.map(_._3).getOrElse(0)

  override private[transaction] def appendBlock(block: Block[TX]): Try[Seq[Block[TX]]] = {
    val parent = block.parentId
    val h = height()
    if ((h == 0) || (lastBlock.id sameElements block.parentId)) {
      blockStorage.changeBestChain(Seq((block, Forward)))
      blockStorage.writeBlock(block).map(x => Seq(block))
    } else blockById(parent) match {
      case Some(commonBlock) =>
        val oldLast = lastBlock
        blockStorage.writeBlock(block) map {
          case true =>
            branchBlock(oldLast, block, MaxRollback) match {
              case Some(node) =>
                val toReverse = oldLast +: lastBlocks(oldLast, heightOf(oldLast).get - heightOf(node).get - 1)
                val toProcess = block +: lastBlocks(block, heightOf(block).get - heightOf(node).get - 1)
                val stateChanges = toReverse.map((_, Reversed)) ++ toProcess.map((_, Forward))
                blockStorage.changeBestChain(stateChanges)
                toProcess
              case None => ??? //Should never rich this point if we don't keep older then MaxRollback side chains
            }
          case false => Seq.empty
        }
      case None => Failure(new Error(s"Appending block ${block.json} which parent is not in block tree"))
    }
  }

  def branchBlock(b1: Block[TX], b2: Block[TX], in: Int): Option[Block[TX]] = {
    val b1LastBlocks = lastBlocks(b1, in)
    find(b2, in)(b => b1LastBlocks.exists(x => x.id sameElements b.id))
  }

  override def heightOf(blockId: BlockId): Option[Int] = blockStorage.readBlock(blockId).map(_._3)

  override def blockById(blockId: BlockId): Option[Block[TX]] = blockStorage.readBlock(blockId).map(_._1)

  override def generatedBy(account: Account): Seq[Block[TX]] = blockStorage.filter { b =>
    consensusModule.producers(b).contains(account)
  }.map(_._1)

  override def lastBlock: Block[TX] = blockStorage.bestBlock.map(_._1).get

  def find(block: Block[TX], limit: Int)(condition: Block[TX] => Boolean): Option[Block[TX]] = if (limit > 0) {
    parent(block) match {
      case Some(pb) =>
        if (condition(pb)) Some(pb)
        else find(pb, limit - 1)(condition)
      case None => None
    }
  } else None

  def lastBlocks(block: Block[TX], howMany: Int): Seq[Block[TX]] = {
    require(howMany >= 0)
    def loop(block: Block[TX], i: Int, acc: Seq[Block[TX]] = Seq.empty): Seq[Block[TX]] = {
      lazy val p = parent(block)
      (i, p) match {
        case (0, _) => acc
        case (m, Some(parentBlock)) => loop(parentBlock, i - 1, parentBlock +: acc)
        case _ => acc
      }
    }
    loop(block, howMany)
  }

  override def lastBlocks(howMany: Int): Seq[Block[TX]] = {
    lastBlocks(lastBlock, howMany).reverse
  }

  override def score(): BigInt = blockStorage.bestBlock.map(_._2).getOrElse(BigInt(0))

  override def parent(block: Block[TX], back: Int = 1): Option[Block[TX]] = {
    require(back > 0)
    val p = blockStorage.readBlock(block.parentId).map(_._1)
    (back, p) match {
      case (1, _) => p
      case (m, Some(parentBlock)) => parent(parentBlock, m - 1)
      case _ => None
    }
  }

  override def lookForward(parentSignature: BlockId, howMany: Int): Seq[BlockId] =
    blockStorage.lookForward(parentSignature, howMany)

  override def contains(id: BlockId): Boolean = blockStorage.exists(id)

  override lazy val genesis: Block[TX] = blockById(Block.genesis().id).get

}

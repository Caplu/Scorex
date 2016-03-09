package scorex.transaction.state.database.blockchain

import java.io.{DataInput, DataOutput, File}

import com.google.common.primitives.Longs
import org.mapdb._
import play.api.libs.json.{JsNumber, JsObject}
import scorex.block.Block
import scorex.crypto.hash.FastCryptographicHash
import scorex.transaction.LagonakiTransaction.ValidationResult
import scorex.transaction._
import scorex.transaction.account.Account
import scorex.utils.ScorexLogging

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.util.Try


/** Store current balances only, and balances changes within effective balance depth.
  * Store transactions for selected accounts only.
  * If no filename provided, blockchain lives in RAM (intended for tests only).
  *
  * Use apply method of StoredState object to create new instance
  */
class StoredState(fileNameOpt: Option[String]) extends LagonakiState with ScorexLogging {

  private object RowSerializer extends Serializer[Row] {
    override def serialize(dataOutput: DataOutput, row: Row): Unit = {
      DataIO.packInt(dataOutput, row.lastRowHeight)
      DataIO.packLong(dataOutput, row.state.balance)
      DataIO.packInt(dataOutput, row.reason.length)
      row.reason.foreach { scr =>
        DataIO.packInt(dataOutput, scr.bytes.length)
        dataOutput.write(scr.bytes)
      }
    }

    override def deserialize(dataInput: DataInput, i: Int): Row = {
      val lastRowHeight = DataIO.unpackInt(dataInput)
      val b = DataIO.unpackLong(dataInput)
      val txCount = DataIO.unpackInt(dataInput)
      val txs = (1 to txCount).toArray.map { _ =>
        val txSize = DataIO.unpackInt(dataInput)
        val b = new Array[Byte](txSize)
        dataInput.readFully(b)
        if (txSize == 8) FeesStateChange(Longs.fromByteArray(b))
        else LagonakiTransaction.parse(b).get //todo: .get w/out catching
      }
      Row(AccState(b), txs, lastRowHeight)
    }
  }

  type Address = String

  case class AccState(balance: Long)

  type Reason = Seq[StateChangeReason]

  case class Row(state: AccState, reason: Reason, lastRowHeight: Int)

  val HeightKey = "height"
  val DataKey = "dataset"
  val LastStates = "lastStates"
  val IncludedTx = "includedTx"

  private val db = fileNameOpt match {
    case Some(fileName) =>
      DBMaker.fileDB(new File(fileName))
        .closeOnJvmShutdown()
        .cacheSize(2048)
        .checksumEnable()
        .fileMmapEnable()
        .make()
    case None => DBMaker.memoryDB().snapshotEnable().make()
  }
  db.rollback()

  override lazy val version: Int = stateHeight

  private def accountChanges(key: Address): HTreeMap[Integer, Row] = db.hashMap(
    key.toString,
    Serializer.INTEGER,
    RowSerializer,
    null)

  val lastStates = db.hashMap[Address, Int](LastStates)

  val includedTx: HTreeMap[Array[Byte], Int] = db.hashMapCreate(IncludedTx)
    .keySerializer(Serializer.BYTE_ARRAY)
    .valueSerializer(Serializer.INTEGER)
    .makeOrGet()

  if (Option(db.atomicInteger(HeightKey).get()).isEmpty) db.atomicInteger(HeightKey).set(0)

  def stateHeight: Int = db.atomicInteger(HeightKey).get()

  private def setStateHeight(height: Int): Unit = db.atomicInteger(HeightKey).set(height)

  private def applyChanges(ch: Map[Address, (AccState, Reason)]): Unit = synchronized {
    setStateHeight(stateHeight + 1)
    val h = stateHeight
    ch.foreach { ch =>
      val change = Row(ch._2._1, ch._2._2, Option(lastStates.get(ch._1)).getOrElse(0))
      accountChanges(ch._1).put(h, change)
      lastStates.put(ch._1, h)
      ch._2._2.foreach(t => includedTx.put(t.serializedProof, h))
    }
    db.commit()
  }

  override def rollbackTo(rollbackTo: Int): StoredState = synchronized {
    def deleteNewer(key: Address): Unit = {
      val currentHeight = lastStates.get(key)
      if (currentHeight > rollbackTo) {
        val dataMap = accountChanges(key)
        val changes = dataMap.remove(currentHeight)
        changes.reason.foreach(t => includedTx.remove(t.serializedProof))
        val prevHeight = changes.lastRowHeight
        lastStates.put(key, prevHeight)
        deleteNewer(key)
      }
    }
    lastStates.keySet().foreach { key =>
      deleteNewer(key)
    }
    setStateHeight(rollbackTo)
    db.commit()
    this
  }

  override def processBlock(block: Block): Try[StoredState] = Try {
    val trans = block.transactions
    trans.foreach(t => if (included(t).isDefined) throw new Error(s"Transaction $t is already in state"))
    val fees: Map[Account, (AccState, Reason)] = block.consensusModule.feesDistribution(block)
      .map(m => m._1 ->(AccState(balance(m._1.address) + m._2), Seq(FeesStateChange(m._2))))

    val newBalances: Map[Account, (AccState, Reason)] = calcNewBalances(trans, fees)
    newBalances.foreach(nb => require(nb._2._1.balance >= 0))

    applyChanges(newBalances.map(a => a._1.address -> a._2))
    log.debug(s"New state height is $stateHeight, hash: $hash, totalBalance: $totalBalance")

    this
  }

  private def calcNewBalances(trans: Seq[Transaction], fees: Map[Account, (AccState, Reason)]):
  Map[Account, (AccState, Reason)] = {
    val newBalances: Map[Account, (AccState, Reason)] = trans.foldLeft(fees) { case (changes, atx) =>
      atx match {
        case tx: LagonakiTransaction =>
          tx.balanceChanges().foldLeft(changes) { case (iChanges, (acc, delta)) =>
            //update balances sheet
            val add = acc.address
            val currentChange: (AccState, Reason) = iChanges.getOrElse(acc, (AccState(balance(add)), Seq.empty))
            iChanges.updated(acc, (AccState(currentChange._1.balance + delta), tx +: currentChange._2))
          }

        case m =>
          throw new Error("Wrong transaction type in pattern-matching" + m)
      }
    }
    newBalances
  }

  override def balanceWithConfirmations(address: String, confirmations: Int): Long =
    balance(address, Some(Math.max(1, stateHeight - confirmations)))

  override def balance(address: String, atHeight: Option[Int] = None): Long = Option(lastStates.get(address)) match {
    case None => 0L
    case Some(h) =>
      val requiredHeight = atHeight.getOrElse(stateHeight)
      require(requiredHeight >= 0, s"Height should not be negative, $requiredHeight given")
      def loop(hh: Int): Long = {
        val row = accountChanges(address).get(hh)
        if (Option(row).isEmpty) log.error(s"accountChanges($address).get($hh) is null")
        if (row.lastRowHeight < requiredHeight) row.state.balance
        else if (row.lastRowHeight == 0) 0L
        else loop(row.lastRowHeight)
      }
      loop(h)
  }

  def totalBalance: Long = lastStates.keySet().map(add => balance(add)).sum

  override def accountTransactions(account: Account): Array[LagonakiTransaction] = {
    Option(lastStates.get(account.address)) match {
      case Some(accHeight) =>
        val m = accountChanges(account.address)
        def loop(h: Int, acc: Array[LagonakiTransaction]): Array[LagonakiTransaction] = Option(m.get(h)) match {
          case Some(heightChanges) =>
            val heightTransactions = heightChanges.reason.toArray.filter(_.isInstanceOf[LagonakiTransaction])
              .map(_.asInstanceOf[LagonakiTransaction])
            loop(heightChanges.lastRowHeight, heightTransactions ++ acc)
          case None => acc
        }
        loop(accHeight, Array.empty)
      case None => Array.empty
    }
  }

  def included(transaction: Transaction,
               heightOpt: Option[Int] = None): Option[Int] = transaction match {
    case tx: LagonakiTransaction =>
      Option(includedTx.get(tx.signature)).filter(_ < heightOpt.getOrElse(Int.MaxValue))
    case _ => throw new Error("wrong kind of transaction")
  }

  //return seq of valid transactions
  @tailrec
  override final def validate(trans: Seq[Transaction], heightOpt: Option[Int] = None): Seq[Transaction] = {
    val height = heightOpt.getOrElse(stateHeight)
    val txs = trans.filter(t => included(t).isEmpty && isValid(t, height))
    val nb = calcNewBalances(txs, Map.empty)
    val negativeBalances: Map[Account, (AccState, Reason)] = nb.filter(b => b._2._1.balance < 0)
    val toRemove = negativeBalances flatMap { b =>
      val accTransactions = trans.filter(_.isInstanceOf[PaymentTransaction]).map(_.asInstanceOf[PaymentTransaction])
        .filter(_.sender.address == b._1.address)
      var sumBalance = b._2._1.balance
      accTransactions.sortBy(-_.amount).takeWhile { t =>
        val prevSum = sumBalance
        sumBalance = sumBalance + t.amount + t.fee
        prevSum < 0
      }
    }
    val validTransactions = txs.filter(t => !toRemove.exists(tr => tr.signature sameElements t.serializedProof))
    if (validTransactions.size == txs.size) txs
    else if (validTransactions.nonEmpty) validate(validTransactions, heightOpt)
    else validTransactions
  }

  private def isValid(transaction: Transaction, height: Int): Boolean = transaction match {
    case tx: PaymentTransaction =>
      tx.signatureValid && tx.validate == ValidationResult.ValidateOke && this.included(tx, Some(height)).isEmpty
    case gtx: GenesisTransaction =>
      height == 0
    case otx: Any =>
      log.error(s"Wrong kind of tx: $otx")
      false
  }

  //for debugging purposes only
  def toJson(heightOpt: Option[Int] = None): JsObject = {
    val ls = lastStates.keySet().map(add => add -> balance(add, heightOpt)).filter(b => b._2 != 0).toList.sortBy(_._1)
    JsObject(ls.map(a => a._1 -> JsNumber(a._2)).toMap)
  }

  //for debugging purposes only
  override def toString: String = toJson().toString()

  def hash: Int = {
    (BigInt(FastCryptographicHash(toString.getBytes())) % Int.MaxValue).toInt
  }
}
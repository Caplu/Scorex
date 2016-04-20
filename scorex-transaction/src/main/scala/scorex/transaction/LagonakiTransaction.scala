package scorex.transaction

import com.google.common.primitives.Ints
import play.api.libs.json.Json
import scorex.transaction.account.Account
import scorex.crypto.encode.Base58
import scorex.transaction.LagonakiTransaction.{ValidationResult, _}
import scorex.transaction.account.AccountTransaction

import scala.concurrent.duration._
import scala.util.Try


abstract class LagonakiTransaction(val transactionType: TransactionType.Value,
                                   override val recipient: Account,
                                   val amount: Long,
                                   override val fee: Long,
                                   override val timestamp: Long,
                                   override val signature: Array[Byte]) extends AccountTransaction  with Serializable {

  lazy val deadline = timestamp + 24.hours.toMillis

  lazy val feePerByte = fee / dataLength.toDouble
  lazy val hasMinimumFee = fee >= MinimumFee
  lazy val hasMinimumFeePerByte = {
    val minFeePerByte = 1.0 / MaxBytesPerToken
    feePerByte >= minFeePerByte
  }

  val TypeId = transactionType.id

  //PARSE/CONVERT
  val dataLength: Int

  val creator: Option[Account]


  val signatureValid: Boolean

  //VALIDATE
  def validate: ValidationResult.Value

  def involvedAmount(account: Account): Long

  def balanceChanges(): Seq[(Account, Long)]

  override def equals(other: Any): Boolean = other match {
    case tx: LagonakiTransaction => signature.sameElements(tx.signature)
    case _ => false
  }

  override def hashCode(): Int = Ints.fromByteArray(signature)

  protected def jsonBase() = {
    Json.obj("type" -> transactionType.id,
      "fee" -> fee,
      "timestamp" -> timestamp,
      "signature" -> Base58.encode(this.signature)
    )
  }
}

object LagonakiTransaction {

  val MaxBytesPerToken = 512

  //MINIMUM FEE
  val MinimumFee = 1
  val RecipientLength = Account.AddressLength
  val TypeLength = 1
  val TimestampLength = 8
  val AmountLength = 8

  object ValidationResult extends Enumeration {
    type ValidationResult = Value

    val ValidateOke = Value(1)
    val InvalidAddress = Value(2)
    val NegativeAmount = Value(3)
    val NegativeFee = Value(4)
    val NoBalance = Value(5)
  }

  //TYPES
  object TransactionType extends Enumeration {
    val GenesisTransaction = Value(1)
    val PaymentTransaction = Value(2)
  }

  def parse(data: Array[Byte]): Try[LagonakiTransaction] = Try {
    data.head match {
      case txType: Byte if txType == TransactionType.GenesisTransaction.id =>
        GenesisTransaction.parse(data.tail)

      case txType: Byte if txType == TransactionType.PaymentTransaction.id =>
        PaymentTransaction.parse(data.tail)

      case txType => throw new Exception(s"Invalid transaction type: $txType")
    }
  }
}

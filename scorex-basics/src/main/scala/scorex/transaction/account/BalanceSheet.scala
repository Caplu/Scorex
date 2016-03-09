package scorex.transaction.account

trait BalanceSheet {
  def balance(address: String, height: Option[Int] = None): Long

  def balanceWithConfirmations(address: String, confirmations: Int): Long

  def generationBalance(address: String): Long = balanceWithConfirmations(address, 50)

  def generationBalance(account: Account): Long = generationBalance(account.address)
}

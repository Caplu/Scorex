package scorex.transaction

import scorex.transaction.account.Account

trait AccountTransactionsHistory {
  def accountTransactions(address: String): Array[_ <: Transaction] = {
    Account.isValidAddress(address) match {
      case false => Array()
      case true =>
        val acc = new Account(address)
        accountTransactions(acc)
    }
  }

  def accountTransactions(account: Account): Array[_ <: Transaction]
}

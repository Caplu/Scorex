package scorex.transaction.state

import scorex.transaction.account.{AccountTransactionsHistory, BalanceSheet}
import scorex.transaction.box.PublicKey25519Proposition

trait LagonakiState extends MinimalState[PublicKey25519Proposition] with BalanceSheet with AccountTransactionsHistory[PublicKey25519Proposition]

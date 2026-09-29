package com.financetracker.model

/**
 * The bank codes the app itself uses.
 *
 * The list of banks a *user* can choose from is data, in the `banks` table — see
 * [BankEntity]. What is left here is the three codes the app is built around: two of them
 * key [com.financetracker.data.statement.BankDetector]'s markers, and Monobank's is what
 * [com.financetracker.data.bank.BankSyncService] stamps on every row it syncs. None of
 * them are validated against anything, because a bank the user added is just as valid as
 * one of these and used to be thrown away for not being on the list.
 */
object BankCode {
    const val MONOBANK = "mo"
    const val UKRSIBBANK = "uk"
    const val PRIVATBANK = "pb"
}

package com.financetracker.model

/**
 * The currencies this app can record a row in, in the order they are offered.
 *
 * A fixed list, and not every ISO code, because the app has no rate feed. It shows per-currency
 * totals and never converts, so a currency is a bucket a row is filed in rather than a number
 * to be translated — and an account in a currency nobody thought to list would be one the user
 * could not file a row in at all. UAH first because it is the app's primary currency, and the
 * one the bonds are denominated in.
 */
val RECORDABLE_CURRENCIES: List<String> = listOf("UAH", "USD", "EUR")

/**
 * The currency a hand-entered row starts in.
 *
 * UAH, and not the device locale's currency. A phone set to en-US would otherwise file a
 * hryvnia grocery bill as a dollar expense, silently, into a ledger that then reports two
 * separate balances for one person. The choice is now visible and changeable, so the only
 * thing left to get right is where it starts.
 */
const val DEFAULT_RECORDABLE_CURRENCY: String = "UAH"

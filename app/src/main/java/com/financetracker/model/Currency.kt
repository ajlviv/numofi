package com.financetracker.model

/**
 * The currencies this app can record a row in, in the order they are offered.
 *
 * A short list and not every ISO code, because a currency here is also one a conversion may be
 * asked for: NBU quotes about forty and this app holds only the ones whose rows it can file,
 * and an account in a currency nobody thought to list would be one the user could not record
 * anything in at all. Anything added here is expected to have a rate on NBU's side — if it does
 * not, `NbuRatesParser` drops it silently and it appears only in the per-currency cards.
 *
 * UAH first because it is the app's primary currency, the one the bonds are denominated in, and
 * the pivot every cached rate is quoted against.
 */
val RECORDABLE_CURRENCIES: List<String> = listOf("UAH", "USD", "EUR", "PLN")

/**
 * The currency a hand-entered row starts in.
 *
 * UAH, and not the device locale's currency. A phone set to en-US would otherwise file a
 * hryvnia grocery bill as a dollar expense, silently, into a ledger that then reports two
 * separate balances for one person. The choice is now visible and changeable, so the only
 * thing left to get right is where it starts.
 */
const val DEFAULT_RECORDABLE_CURRENCY: String = "UAH"

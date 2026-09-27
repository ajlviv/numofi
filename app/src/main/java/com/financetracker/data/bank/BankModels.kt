package com.financetracker.data.bank

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A bank account or card.
 *
 * Money is [BigDecimal] in *major* units (UAH, not kopecks) because bank statement
 * data is money and binary floating point cannot represent values like 0.1 exactly.
 */
data class BankAccount(
    val id: String,
    val bankId: String?,
    val name: String,
    val type: String,
    val currencyCode: Int,
    val balance: BigDecimal,
    val creditLimit: BigDecimal?,
    val maskedPan: List<String>,
    val iban: String?
)

/**
 * A single statement line.
 *
 * [amount] is signed as the bank reports it: negative for money leaving the account,
 * positive for money arriving. Use [isExpense] rather than testing the sign yourself.
 */
data class BankTransaction(
    val id: String,
    val accountId: String,
    val timestamp: Long,
    val description: String,
    val amount: BigDecimal,
    val currencyCode: Int,
    val balanceAfter: BigDecimal?,
    val comment: String?,
    val counterName: String?,
    val isHold: Boolean,
    val mcc: Int?
) {
    val isExpense: Boolean get() = amount.signum() < 0
    val isIncome: Boolean get() = amount.signum() > 0
    val absoluteAmount: BigDecimal get() = amount.abs()
}

/**
 * ISO 4217 helpers. Banks report amounts as integers in each currency's minor unit
 * and identify currencies by their numeric code, so conversion needs the currency's
 * exponent rather than a blanket divide-by-100.
 */
object Iso4217 {

    private val EXPONENTS: Map<Int, Int> = mapOf(
        980 to 2, // UAH
        840 to 2, // USD
        978 to 2, // EUR
        643 to 2, // RUB
        756 to 2, // CHF
        826 to 2, // GBP
        392 to 0, // JPY
        410 to 0, // KRW
        48 to 3, // BHD
        414 to 3, // KWD
        422 to 3, // JOD
        634 to 3  // OMR
    )

    private val SYMBOLS: Map<Int, String> = mapOf(
        980 to "UAH",
        840 to "USD",
        978 to "EUR",
        643 to "RUB",
        756 to "CHF",
        826 to "GBP",
        392 to "JPY"
    )

    fun exponentOf(currencyCode: Int): Int = EXPONENTS[currencyCode] ?: 2

    fun symbolOf(currencyCode: Int): String = SYMBOLS[currencyCode] ?: "CUR$currencyCode"

    /** Converts an integer in minor units to major units. */
    fun toMajorUnits(amountInMinorUnits: Long, currencyCode: Int): BigDecimal =
        BigDecimal.valueOf(amountInMinorUnits)
            .movePointLeft(exponentOf(currencyCode))
            .setScale(exponentOf(currencyCode), RoundingMode.UNNECESSARY)
}

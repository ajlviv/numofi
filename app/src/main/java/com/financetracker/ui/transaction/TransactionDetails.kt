package com.financetracker.ui.transaction

import com.financetracker.model.Transaction
import com.financetracker.util.CategoryLabel
import com.financetracker.util.DateFormats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A single stored value as the detail card shows it.
 *
 * [raw] marks the fields printed exactly as stored rather than formatted for a person, so a
 * row can be checked against the statement it came from. Nothing is derived for these: a
 * formatted amount rounds, a formatted date hides the zone, and neither can be compared
 * against the file.
 */
data class DetailField(
    val label: String,
    val value: String,
    val raw: Boolean = false
)

data class DetailSection(
    val title: String,
    val fields: List<DetailField>
)

/**
 * Every value the app stores about a transaction, grouped for reading.
 *
 * Kept apart from the screen so the mapping can be tested without a Compose runtime. The
 * card is the only place a user can inspect a row, and a field added here but not shown is
 * invisible: nothing else would report it missing.
 *
 * The sections are exhaustive over [Transaction], which is everything the detail screen is
 * given. Two further columns exist in the table but not on that type, and so cannot appear
 * here without widening it: the owning user id, and the lowercased search haystack, which is
 * derived from the other fields rather than entered.
 */
object TransactionDetails {

    /** Stands in for an absent value, so a gap is visible instead of inferred. */
    const val MISSING = "Not set"

    private val DATE: DateTimeFormatter = DateFormats.dateTime()

    fun sections(
        transaction: Transaction,
        bankName: String,
        zone: ZoneId = ZoneId.systemDefault(),
        dateFormat: DateTimeFormatter = DATE
    ): List<DetailSection> {
        val at = Instant.ofEpochMilli(transaction.timestamp).atZone(zone).toLocalDateTime()
        return listOf(
            DetailSection(
                title = "Transaction",
                fields = listOf(
                    DetailField("Title", transaction.title),
                    DetailField("Category", CategoryLabel.label(transaction.category)),
                    DetailField("Date", dateFormat.format(at)),
                    DetailField("Note", transaction.note ?: MISSING)
                )
            ),
            DetailSection(
                title = "Payment",
                fields = listOf(
                    DetailField("Type", transaction.type.name, raw = true),
                    DetailField("Currency (ISO 4217)", transaction.currencyCode ?: MISSING, raw = true),
                    // Printed unformatted: the headline above the card rounds for reading, and
                    // only the stored value can be compared against an imported statement.
                    DetailField("Amount (stored)", transaction.amount.toString(), raw = true)
                )
            ),
            DetailSection(
                title = "Provenance",
                fields = listOf(
                    // Resolved by the caller, which is the only layer that knows the bank
                    // list. "Manual" for a hand-entered row is a real answer rather than a
                    // missing one, so it is not passed through MISSING.
                    DetailField("Bank", bankName),
                    DetailField("Card", transaction.cardLabel ?: MISSING, raw = true),
                    DetailField("Source", transaction.source ?: MISSING, raw = true),
                    DetailField("External ID", transaction.externalId ?: MISSING, raw = true)
                )
            ),
            DetailSection(
                title = "Stored",
                fields = listOf(
                    DetailField("Row ID", transaction.id.toString(), raw = true),
                    DetailField("Timestamp (epoch ms)", transaction.timestamp.toString(), raw = true)
                )
            )
        )
    }
}

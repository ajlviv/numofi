package com.financetracker.ui.transaction

import com.financetracker.data.AppDatabase
import com.financetracker.model.BankCode
import com.financetracker.model.BankNames
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TransactionDetailsTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    private fun transaction(
        id: Long = 7,
        note: String? = null,
        currencyCode: String? = "UAH",
        externalId: String? = null,
        source: String? = null,
        bankCode: String? = null,
        cardLabel: String? = null
    ) = Transaction(
        id = id,
        title = "TORUS",
        amount = 1234.56,
        type = TransactionType.EXPENSE,
        category = "mcc_5499",
        // 2026-09-14T09:30 in Kyiv.
        timestamp = LocalDate.of(2026, 9, 14).atTime(9, 30).atZone(kyiv).toInstant().toEpochMilli(),
        note = note,
        currencyCode = currencyCode,
        externalId = externalId,
        source = source,
        bankCode = bankCode,
        cardLabel = cardLabel
    )

    /**
     * Resolved the same way the detail screen does it, rather than being handed a name.
     *
     * Handing it a fixed name would make every bank case render identically, so the tests
     * below would keep passing even if resolution changed — which is exactly the part of
     * this feature they exist to check.
     */
    private fun sections(t: Transaction, zone: ZoneId = kyiv) = TransactionDetails.sections(
        t,
        BankNames.display(t.bankCode, AppDatabase.SEEDED_BANK_NAMES),
        zone,
        dateFormat
    )

    private fun fields(t: Transaction) = sections(t).flatMap { it.fields }

    private fun value(t: Transaction, label: String) =
        fields(t).single { it.label == label }.value

    @Test
    fun `every stored field is surfaced exactly once`() {
        // The point of the card. A field added to [Transaction] and not shown here would be
        // invisible on the one screen a user can inspect a row on, and no other test would
        // report it, so the full label set is pinned rather than spot-checked.
        val labels = fields(transaction()).map { it.label }
        assertEquals(
            listOf(
                "Title",
                "Category",
                "Date",
                "Note",
                "Type",
                "Currency (ISO 4217)",
                "Amount (stored)",
                "Bank",
                "Card",
                "Source",
                "External ID",
                "Row ID",
                "Timestamp (epoch ms)"
            ),
            labels
        )
    }

    @Test
    fun `an imported row shows where it came from`() {
        val t = transaction(
            externalId = "PB/20260914/0001",
            source = "privatbank",
            bankCode = BankCode.PRIVATBANK,
            cardLabel = "4111****2222"
        )
        assertEquals("PrivatBank", value(t, "Bank"))
        assertEquals("4111****2222", value(t, "Card"))
        assertEquals("privatbank", value(t, "Source"))
        assertEquals("PB/20260914/0001", value(t, "External ID"))
    }

    @Test
    fun `a hand-entered row says so rather than looking broken`() {
        // A null bank is not a missing value: it means the user typed the row in.
        assertEquals("Manual", value(transaction(), "Bank"))
    }

    @Test
    fun `an unknown bank code is shown as stored`() {
        // Blanking it would make an unrecognised code indistinguishable from a manual row.
        assertEquals("paypal", value(transaction(bankCode = "paypal"), "Bank"))
    }

    @Test
    fun `an absent value is marked instead of left blank`() {
        val t = transaction(currencyCode = null)
        listOf("Note", "Card", "Source", "External ID", "Currency (ISO 4217)").forEach { label ->
            assertEquals("'$label'", TransactionDetails.MISSING, value(t, label))
        }
    }

    @Test
    fun `the stored amount and timestamp are unformatted`() {
        val t = transaction(id = 42)
        assertEquals("1234.56", value(t, "Amount (stored)"))
        assertEquals("42", value(t, "Row ID"))
        assertEquals(t.timestamp.toString(), value(t, "Timestamp (epoch ms)"))
    }

    @Test
    fun `the date is rendered in the requested zone`() {
        val t = transaction()
        assertEquals("2026-09-14 09:30", value(t, "Date"))
    }

    @Test
    fun `the same instant is a different calendar day in another zone`() {
        // Proof the date is not printed in the device zone regardless of the argument: this is
        // what a timestamp means depends on where you are standing.
        val t = transaction()
        val utc = sections(t, ZoneId.of("UTC"))
            .flatMap { it.fields }
            .single { it.label == "Date" }
            .value
        assertEquals("2026-09-14 06:30", utc)
    }

    @Test
    fun `the category is labelled rather than shown as a stored key`() {
        assertEquals("Groceries", value(transaction(), "Category"))
    }

    @Test
    fun `only the values that are stored verbatim are marked raw`() {
        val byLabel = fields(transaction()).associate { it.label to it.raw }
        assertTrue(byLabel.getValue("Amount (stored)"))
        assertTrue(byLabel.getValue("Timestamp (epoch ms)"))
        assertTrue(byLabel.getValue("External ID"))
        assertFalse(byLabel.getValue("Title"))
        assertFalse(byLabel.getValue("Category"))
        assertFalse(byLabel.getValue("Date"))
    }

    @Test
    fun `a section never repeats a label`() {
        sections(transaction()).forEach { section ->
            val labels = section.fields.map { it.label }
            assertEquals(
                "duplicate label in '${section.title}'",
                labels.size,
                labels.toSet().size
            )
        }
    }
}

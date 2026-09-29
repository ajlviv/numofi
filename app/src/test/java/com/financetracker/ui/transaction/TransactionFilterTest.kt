package com.financetracker.ui.transaction

import com.financetracker.model.BankCode
import com.financetracker.model.BankRef
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class TransactionFilterTest {

    private fun Transaction(
        bankCode: String? = null,
        cardLabel: String? = null
    ) = Transaction(
        title = "TORUS",
        amount = 10.0,
        type = TransactionType.EXPENSE,
        category = "grocery",
        timestamp = 0,
        bankCode = bankCode,
        cardLabel = cardLabel
    )

    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `toggling adds an unselected value and removes a selected one`() {
        val on = TransactionFilter().toggledBank(BankCode.PRIVATBANK)
        assertEquals(setOf(BankCode.PRIVATBANK), on.bankCodes)
        assertEquals(
            TransactionFilter(),
            on.toggledBank(BankCode.PRIVATBANK)
        )
    }

    @Test
    fun `toggling one filter leaves the others alone`() {
        val filter = TransactionFilter(
            bankCodes = setOf(BankCode.MONOBANK),
            type = TransactionTypeFilter.INCOME
        )
        assertEquals(
            TransactionFilter(
                bankCodes = setOf(BankCode.MONOBANK, BankCode.PRIVATBANK),
                type = TransactionTypeFilter.INCOME
            ),
            filter.toggledBank(BankCode.PRIVATBANK)
        )
    }

    @Test
    fun `the all chip asks for no type constraint at all`() {
        // An empty list is what the DAO reads as "no constraint". Binding a list of all three
        // types would instead be a second way of spelling the same query, and a fourth one the
        // moment another type is added.
        assertEquals(emptyList<TransactionType>(), TransactionFilter().types)
    }

    @Test
    fun `each type chip binds exactly the one type it names`() {
        assertEquals(
            listOf(TransactionType.TRANSFER),
            TransactionFilter(type = TransactionTypeFilter.TRANSFERS).types
        )
        assertEquals(
            listOf(TransactionType.EXPENSE),
            TransactionFilter(type = TransactionTypeFilter.EXPENSE).types
        )
    }

    @Test
    fun `a type other than all makes the filter active`() {
        assertTrue(TransactionFilter(type = TransactionTypeFilter.TRANSFERS).isActive)
    }

    @Test
    fun `a bond trade is a transfer and is not an expense`() {
        // The whole reason TRANSFER is a type of its own. Filing a purchase under EXPENSE
        // would make buying a bond look identical to spending the money, and the balance
        // would have to treat the two the same way to be correct.
        val trade = Transaction(
            title = "Купівля ОвДП 24/Б",
            amount = 995.0,
            type = TransactionType.TRANSFER,
            category = "investments",
            timestamp = 0,
            transferDirection = TransferDirection.OUT
        )
        assertFalse(trade.isExpense())
        assertFalse(trade.isIncome())
        assertTrue(trade.isCashOutflow())
    }

    @Test
    fun `pruning drops the cards the new banks rule out and keeps the rest`() {
        // Changing one bank must not discard a card that still belongs to a selected bank,
        // which is what clearing the card outright used to do.
        val filter = TransactionFilter(
            bankCodes = setOf(BankCode.MONOBANK, BankCode.UKRSIBBANK),
            cardLabels = setOf("mo-card", "uk-card")
        )
        assertEquals(
            setOf("uk-card"),
            filter.prunedTo(setOf("uk-card", "unrelated-card")).cardLabels
        )
    }

    @Test
    fun `pruning every card leaves the banks untouched`() {
        val filter = TransactionFilter(
            bankCodes = setOf(BankCode.UKRSIBBANK),
            cardLabels = setOf("mo-card")
        )
        val pruned = filter.prunedTo(setOf("uk-card"))
        assertEquals(emptySet<String>(), pruned.cardLabels)
        assertEquals(setOf(BankCode.UKRSIBBANK), pruned.bankCodes)
    }

    @Test
    fun `an empty filter claims to be inactive`() {
        assertFalse(TransactionFilter().isActive)
        assertTrue(TransactionFilter().toggledCard("mo-card").isActive)
        assertTrue(TransactionFilter(from = LocalDate.of(2026, 9, 1)).isActive)
        assertTrue(TransactionFilter(to = LocalDate.of(2026, 9, 1)).isActive)
    }

    @Test
    fun `an unset bound asks for no constraint at all`() {
        val filter = TransactionFilter()
        assertNull(filter.startMillis(kyiv))
        assertNull(filter.endMillis(kyiv))
    }

    @Test
    fun `a single day range starts at its first millisecond and ends at the next midnight`() {
        // The end is exclusive, so the last instant of the day is inside the range without
        // the caller having to know how long a day is.
        val day = LocalDate.of(2026, 9, 14)
        val filter = TransactionFilter(from = day, to = day)
        val start = filter.startMillis(kyiv)!!
        val end = filter.endMillis(kyiv)!!

        assertEquals(day.atStartOfDay(kyiv).toInstant().toEpochMilli(), start)
        assertEquals(day.plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli(), end)
        assertEquals(Duration.ofDays(1).toMillis(), end - start)
    }

    @Test
    fun `a day that is not 24 hours long is still bounded by its own midnights`() {
        // 8 March 2026 is a 23-hour day in New York. Adding 24 hours to the start would run
        // an hour past the end of the range and pull in a transaction from the next morning.
        val day = LocalDate.of(2026, 3, 8)
        val filter = TransactionFilter(from = day, to = day)
        val start = filter.startMillis(newYork)!!
        val end = filter.endMillis(newYork)!!

        assertEquals(Duration.ofHours(23).toMillis(), end - start)
    }

    @Test
    fun `the two ends of a range are independent`() {
        val filter = TransactionFilter(from = LocalDate.of(2026, 3, 1))
        assertTrue(filter.startMillis(kyiv) != null)
        assertNull(filter.endMillis(kyiv))
    }

    @Test
    fun `presets end today and span the period they name`() {
        val today = LocalDate.of(2026, 9, 28)
        assertEquals(
            LocalDate.of(2026, 9, 1) to today,
            DatePreset.THIS_MONTH.range(today)
        )
        assertEquals(
            LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 31),
            DatePreset.LAST_MONTH.range(today)
        )
        // Inclusive of both ends, so thirty days is a 29-day offset and ninety is 89.
        assertEquals(
            LocalDate.of(2026, 8, 30) to today,
            DatePreset.LAST_30_DAYS.range(today)
        )
        assertEquals(
            LocalDate.of(2026, 7, 1) to today,
            DatePreset.LAST_90_DAYS.range(today)
        )
        assertEquals(
            LocalDate.of(2026, 1, 1) to today,
            DatePreset.YEAR_TO_DATE.range(today)
        )
    }

    @Test
    fun `a user added bank code is kept verbatim`() {
        // Bank codes used to be checked against a fixed list and an unrecognised one dropped.
        // The list is the user's now, so a code the filter has never seen is kept and
        // resolved against the bank table when the option is built.
        val on = TransactionFilter().toggledBank("bank-0a1b2c")
        assertEquals(setOf("bank-0a1b2c"), on.bankCodes)
    }

    @Test
    fun `the provenance line omits what is unknown`() {
        val bank = BankRef(BankCode.MONOBANK, "Monobank")
        val withCard = Transaction(cardLabel = "4111****2222")
        val mono = Transaction(bankCode = BankCode.MONOBANK)

        assertEquals("Monobank • 4111****2222", withCard.provenance(bank))
        assertEquals("Monobank", mono.provenance(bank))
        // A hand-entered row has neither, so it gets no line at all.
        assertNull(Transaction().provenance(null))
    }

    @Test
    fun `an unknown stored code is still shown rather than blanked`() {
        // The name is unresolvable, but the row definitely came from somewhere, and showing
        // the raw code keeps it distinguishable from a hand-entered row.
        val bank = BankRef("paypal", "paypal")
        assertEquals("paypal", Transaction(bankCode = "paypal").provenance(bank))
    }
}

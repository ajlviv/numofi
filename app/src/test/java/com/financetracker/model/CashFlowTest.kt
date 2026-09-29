package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cash arithmetic behind the dashboard balance.
 *
 * A transfer moves money between pockets rather than being earned or spent, and the whole
 * reason it exists is that buying a bond is not an expense. The properties worth pinning here
 * are that transfers move the balance, and that they are never quietly counted as spending.
 */
class CashFlowTest {

    private fun tx(
        amount: Double,
        type: TransactionType,
        direction: TransferDirection? = null,
        currency: String? = "UAH"
    ) = Transaction(
        title = "row",
        amount = amount,
        type = type,
        category = "investments",
        timestamp = 0,
        currencyCode = currency,
        transferDirection = direction
    )

    private fun out(amount: Double) = tx(amount, TransactionType.TRANSFER, TransferDirection.OUT)

    private fun `in`(amount: Double) = tx(amount, TransactionType.TRANSFER, TransferDirection.IN)

    @Test
    fun `buying a bond moves money out without being an expense`() {
        // The defect this prevents: a bond purchase recorded as an expense inflates the
        // spending figure, and the one number the app reports most is spending.
        val totals = totalsByCurrency(
            listOf(
                tx(1000.0, TransactionType.INCOME),
                out(400.0),
                tx(120.0, TransactionType.EXPENSE)
            )
        ).single()

        assertEquals(120.0, totals.expense, 0.0)
        assertEquals(1000.0, totals.income, 0.0)
        assertEquals(480.0, totals.balance, 0.0)
    }

    @Test
    fun `selling a bond moves money back in`() {
        val totals = totalsByCurrency(listOf(tx(1000.0, TransactionType.INCOME), `in`(400.0)))
            .single()

        assertEquals(1000.0, totals.income, 0.0)
        assertEquals(0.0, totals.expense, 0.0)
        assertEquals(1400.0, totals.balance, 0.0)
    }

    @Test
    fun `transfers in and out are totalled separately from income and expense`() {
        // Kept apart rather than folded in, because "you spent 1000" and "you moved 1000
        // into a bond" are different statements and the summary panel shows both.
        val totals = totalsByCurrency(
            listOf(
                out(400.0),
                `in`(50.0),
                tx(200.0, TransactionType.EXPENSE),
                tx(900.0, TransactionType.INCOME)
            )
        ).single()

        assertEquals(400.0, totals.transferOut, 0.0)
        assertEquals(50.0, totals.transferIn, 0.0)
        assertEquals(900.0 + 50.0 - 200.0 - 400.0, totals.balance, 0.0)
    }

    @Test
    fun `a transfer settles in the currency the account actually holds`() {
        // A bond bought with a USD account moves USD. It must not land in the UAH group,
        // because there is no rate here that could make that a correct number.
        val totals = totalsByCurrency(
            listOf(
                tx(1000.0, TransactionType.INCOME, currency = "UAH"),
                out(400.0).copy(currencyCode = "USD")
            )
        )

        assertEquals(listOf("UAH", "USD"), totals.map { it.currencyCode })
        assertEquals(1000.0, totals.single { it.currencyCode == "UAH" }.balance, 0.0)
        assertEquals(-400.0, totals.single { it.currencyCode == "USD" }.balance, 0.0)
    }

    @Test
    fun `isIncome and isExpense keep meaning what they meant before transfers existed`() {
        // Import paths and the summary panel both call these, and none of them should start
        // treating a bond purchase as spending just because a third type now exists.
        assertTrue(tx(1.0, TransactionType.INCOME).isIncome())
        assertTrue(tx(1.0, TransactionType.EXPENSE).isExpense())
        assertFalse(out(1.0).isIncome())
        assertFalse(out(1.0).isExpense())
    }

    @Test
    fun `cash in and cash out cover all three types exactly once`() {
        assertTrue(tx(1.0, TransactionType.INCOME).isCashInflow())
        assertFalse(tx(1.0, TransactionType.INCOME).isCashOutflow())
        assertTrue(tx(1.0, TransactionType.EXPENSE).isCashOutflow())
        assertFalse(tx(1.0, TransactionType.EXPENSE).isCashInflow())
        assertTrue(out(1.0).isCashOutflow())
        assertFalse(out(1.0).isCashInflow())
        assertTrue(`in`(1.0).isCashInflow())
        assertFalse(`in`(1.0).isCashOutflow())
    }

    @Test
    fun `a transfer with no direction counts as neither way`() {
        // A row written before the column existed has a null direction. It must not be
        // guessed into a side of the balance, or old rows would silently move cash.
        val legacy = tx(500.0, TransactionType.TRANSFER)
        assertFalse(legacy.isCashInflow())
        assertFalse(legacy.isCashOutflow())
    }

    @Test
    fun `a non-transfer row with a stray direction is judged by its type alone`() {
        val row = tx(100.0, TransactionType.INCOME, TransferDirection.OUT)
        assertTrue(row.isCashInflow())
        assertFalse(row.isCashOutflow())
    }
}

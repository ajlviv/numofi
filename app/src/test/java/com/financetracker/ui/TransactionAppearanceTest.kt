package com.financetracker.ui

import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A bond purchase is the reason transfers exist, and the reason a bond purchase must not be
 * painted like an expense. These assertions are the whole point of the neutral styling: a
 * future "simplification" that folds transfers back into the green/red binary fails here.
 */
class TransactionAppearanceTest {

    private fun tx(
        type: TransactionType,
        direction: TransferDirection? = null
    ) = Transaction(
        title = "row",
        amount = 100.0,
        type = type,
        category = "investments",
        timestamp = 0,
        currencyCode = "UAH",
        transferDirection = direction
    )

    @Test
    fun `a bond purchase is not coloured as spending`() {
        val purchase = tx(TransactionType.TRANSFER, TransferDirection.OUT)

        assertNotEquals(TransactionAppearance.Expense, TransactionAppearance.accent(purchase))
        assertNotEquals(TransactionAppearance.ExpenseTint, TransactionAppearance.tint(purchase))
        assertNotEquals(
            TransactionAppearance.accent(tx(TransactionType.EXPENSE)),
            TransactionAppearance.accent(purchase)
        )
    }

    @Test
    fun `a bond sale is not coloured as income`() {
        // The other half of the same mistake: a sale proceeds the user, but it is not a
        // payday, and colouring it green would inflate income the same way.
        val sale = tx(TransactionType.TRANSFER, TransferDirection.IN)

        assertNotEquals(TransactionAppearance.Income, TransactionAppearance.accent(sale))
        assertNotEquals(TransactionAppearance.IncomeTint, TransactionAppearance.tint(sale))
    }

    @Test
    fun `every transfer looks the same regardless of direction`() {
        // Direction is carried by the sign, not the colour, so the neutral styling can hold
        // for both without a fourth green and a fourth red.
        val out = tx(TransactionType.TRANSFER, TransferDirection.OUT)
        val `in` = tx(TransactionType.TRANSFER, TransferDirection.IN)

        assertEquals(TransactionAppearance.accent(out), TransactionAppearance.accent(`in`))
        assertEquals(TransactionAppearance.tint(out), TransactionAppearance.tint(`in`))
        assertEquals(TransactionAppearance.icon(out), TransactionAppearance.icon(`in`))
    }

    @Test
    fun `the sign follows the money, not the type`() {
        assertEquals("+", TransactionAppearance.signPrefix(tx(TransactionType.INCOME)))
        assertEquals("-", TransactionAppearance.signPrefix(tx(TransactionType.EXPENSE)))
        assertEquals("-", TransactionAppearance.signPrefix(tx(TransactionType.TRANSFER, TransferDirection.OUT)))
        assertEquals("+", TransactionAppearance.signPrefix(tx(TransactionType.TRANSFER, TransferDirection.IN)))
    }

    @Test
    fun `a transfer with no direction gets no invented sign`() {
        // Showing "+500" for a row whose direction was never recorded would be a guess about
        // the user's balance, which is the one thing this screen is read for.
        assertEquals("", TransactionAppearance.signPrefix(tx(TransactionType.TRANSFER)))
    }

    @Test
    fun `income and expense keep the colours they had`() {
        assertEquals(TransactionAppearance.Income, TransactionAppearance.accent(tx(TransactionType.INCOME)))
        assertEquals(TransactionAppearance.Expense, TransactionAppearance.accent(tx(TransactionType.EXPENSE)))
        assertEquals(TransactionAppearance.IncomeTint, TransactionAppearance.tint(tx(TransactionType.INCOME)))
        assertEquals(TransactionAppearance.ExpenseTint, TransactionAppearance.tint(tx(TransactionType.EXPENSE)))
    }
}

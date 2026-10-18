package com.financetracker.ui

import androidx.compose.ui.graphics.Color
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.ui.theme.CONTRAST_AA
import com.financetracker.ui.theme.CONTRAST_AAA
import com.financetracker.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        // The icon moved to CategoryIcon when rows started wearing their category; the
        // invariant it asserts is unchanged — both directions wear the same arrow.
        assertEquals(CategoryIcon.icon(out), CategoryIcon.icon(`in`))
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

    @Test
    fun `the light accents clear AA on the light surfaces an amount lands on`() {
        // Amounts are drawn at 14–16sp, which does not qualify as WCAG "large text", so the
        // 4.5:1 bar is the one that applies.
        for (surface in listOf(Color.White, Color(0xFFFAFAFA))) {
            for (accent in listOf(
                TransactionAppearance.Income,
                TransactionAppearance.Expense,
                TransactionAppearance.Transfer
            )) {
                assertTrue(
                    "${accent} on $surface is ${contrastRatio(accent, surface)}",
                    contrastRatio(accent, surface) >= CONTRAST_AA
                )
            }
        }
    }

    @Test
    fun `the dark accents clear AAA on the dark surfaces an amount lands on`() {
        // The dark surfaces are where the light values failed — 3.0–3.3:1 — so the bar here
        // is AAA rather than the AA floor, and it is reachable without losing the hue.
        for (surface in listOf(Color(0xFF1C1C1C), Color(0xFF121212))) {
            for (accent in listOf(
                TransactionAppearance.IncomeOnDark,
                TransactionAppearance.ExpenseOnDark,
                TransactionAppearance.TransferOnDark
            )) {
                assertTrue(
                    "${accent} on $surface is ${contrastRatio(accent, surface)}",
                    contrastRatio(accent, surface) >= CONTRAST_AAA
                )
            }
        }
    }

    @Test
    fun `every accent clears AA on the tint it is drawn over`() {
        // The icon boxes and the type badge never darken with the theme, so the light values
        // are the only ones that ever sit on a tint — in either mode.
        listOf(
            TransactionAppearance.Income to TransactionAppearance.IncomeTint,
            TransactionAppearance.Expense to TransactionAppearance.ExpenseTint,
            TransactionAppearance.Transfer to TransactionAppearance.TransferTint
        ).forEach { (accent, tint) ->
            assertTrue(
                "$accent on $tint is ${contrastRatio(accent, tint)}",
                contrastRatio(accent, tint) >= CONTRAST_AA
            )
        }
    }

    @Test
    fun `an amount takes the variant its surface can carry`() {
        val purchase = tx(TransactionType.TRANSFER, TransferDirection.OUT)

        assertEquals(
            TransactionAppearance.accent(purchase),
            TransactionAppearance.accentOn(Color.White, purchase)
        )
        assertEquals(
            TransactionAppearance.accentOnDark(purchase),
            TransactionAppearance.accentOn(Color(0xFF1C1C1C), purchase)
        )
        // And the same for the screens that carry a direction rather than a transaction.
        assertEquals(TransactionAppearance.Expense, TransactionAppearance.accentOn(Color.White, outgoing = true))
        assertEquals(TransactionAppearance.Income, TransactionAppearance.accentOn(Color.White, outgoing = false))
        assertEquals(
            TransactionAppearance.ExpenseOnDark,
            TransactionAppearance.accentOn(Color(0xFF121212), outgoing = true)
        )
    }

    @Test
    fun `a bond purchase is still not coloured as spending in dark mode`() {
        // The invariant that made transfers exist, re-checked against the surface where the
        // old palette actually failed: a brightened red must not pick up a brightened green.
        val purchase = tx(TransactionType.TRANSFER, TransferDirection.OUT)
        val expense = tx(TransactionType.EXPENSE)
        val income = tx(TransactionType.INCOME)

        for (surface in listOf(Color.White, Color(0xFF1C1C1C))) {
            assertNotEquals(
                TransactionAppearance.accentOn(surface, expense),
                TransactionAppearance.accentOn(surface, purchase)
            )
            assertNotEquals(
                TransactionAppearance.accentOn(surface, income),
                TransactionAppearance.accentOn(surface, purchase)
            )
        }
    }
}

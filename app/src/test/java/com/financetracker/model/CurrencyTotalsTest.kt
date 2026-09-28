package com.financetracker.model

import com.financetracker.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyTotalsTest {

    private fun tx(
        amount: Double,
        type: TransactionType,
        currency: String?
    ) = Transaction(
        title = "row",
        amount = amount,
        type = type,
        category = "grocery",
        timestamp = 0,
        currencyCode = currency
    )

    @Test
    fun `an empty history has nothing to total`() {
        assertEquals(emptyList<CurrencyTotals>(), totalsByCurrency(emptyList()))
    }

    @Test
    fun `one currency produces one balance`() {
        val totals = totalsByCurrency(
            listOf(
                tx(1000.0, TransactionType.INCOME, "UAH"),
                tx(250.0, TransactionType.EXPENSE, "UAH")
            )
        )

        assertEquals(1, totals.size)
        assertEquals("UAH", totals.single().currencyCode)
        assertEquals(1000.0, totals.single().income, 0.0)
        assertEquals(250.0, totals.single().expense, 0.0)
        assertEquals(750.0, totals.single().balance, 0.0)
    }

    @Test
    fun `two currencies are totalled separately, never added together`() {
        // The defect: summing 1000 UAH and 300 USD gave 1300, then labelled it with whichever
        // currency happened to be most common. There is no correct value for that number.
        val totals = totalsByCurrency(
            listOf(
                tx(1000.0, TransactionType.INCOME, "UAH"),
                tx(300.0, TransactionType.INCOME, "USD"),
                tx(100.0, TransactionType.EXPENSE, "UAH")
            )
        )

        assertEquals(2, totals.size)
        val uah = totals.single { it.currencyCode == "UAH" }
        val usd = totals.single { it.currencyCode == "USD" }
        assertEquals(900.0, uah.balance, 0.0)
        assertEquals(300.0, usd.balance, 0.0)
        assertEquals(1000.0 + 300.0, uah.income + usd.income, 0.0)
    }

    @Test
    fun `a row with no currency is not folded into a real one`() {
        // Hand-entered rows can lack a currency, and a missing code is not the same money as
        // a known one, so it stands on its own rather than inflating UAH.
        val totals = totalsByCurrency(
            listOf(
                tx(100.0, TransactionType.EXPENSE, "UAH"),
                tx(70.0, TransactionType.EXPENSE, null)
            )
        )

        assertEquals(2, totals.size)
        assertEquals(100.0, totals.single { it.currencyCode == "UAH" }.expense, 0.0)
        assertEquals(70.0, totals.single { it.currencyCode == null }.expense, 0.0)
    }

    @Test
    fun `the most recently used currency comes first`() {
        // Written newest first, the order the caller supplies, so the most recent row is USD.
        val totals = totalsByCurrency(
            listOf(
                tx(20.0, TransactionType.EXPENSE, "USD"),
                tx(10.0, TransactionType.EXPENSE, "UAH")
            )
        )

        assertEquals(listOf("USD", "UAH"), totals.map { it.currencyCode })
    }

    @Test
    fun `a zero balance is still shown for a currency that has rows`() {
        val totals = totalsByCurrency(
            listOf(
                tx(100.0, TransactionType.INCOME, "UAH"),
                tx(100.0, TransactionType.EXPENSE, "UAH")
            )
        )

        assertEquals(0.0, totals.single().balance, 0.0)
    }
}

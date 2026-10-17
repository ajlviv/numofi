package com.financetracker.model

import com.financetracker.util.CategoryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTotalsTest {

    private val rates = ExchangeRates(
        toUah = mapOf("USD" to 40.0, "EUR" to 50.0),
        date = "01.10.2026",
        fetchedAt = 0L
    )

    private fun tx(
        amount: Double,
        type: TransactionType = TransactionType.EXPENSE,
        category: String = "imported",
        currency: String? = "UAH",
        direction: TransferDirection? = null
    ) = Transaction(
        id = 0L,
        title = "row",
        amount = amount,
        type = type,
        category = category,
        timestamp = 0L,
        currencyCode = currency,
        transferDirection = direction
    )

    private fun of(rows: List<Transaction>) =
        categoryTotals(rows, rates, "UAH", { CategoryLabel.label(it) })

    @Test
    fun `an empty history has nothing to break down`() {
        val result = of(emptyList())
        assertTrue(result.isBaseQuotable)
        assertTrue(result.income.isEmpty())
        assertTrue(result.expense.isEmpty())
        assertEquals(0.0, result.totalIncome, 0.0)
        assertEquals(0.0, result.totalExpense, 0.0)
        assertTrue(result.unquoted.isEmpty())
    }

    @Test
    fun `income and expense are grouped separately, each by category`() {
        val result = of(
            listOf(
                tx(8_000.0, TransactionType.INCOME, "imported"),
                tx(300.0, TransactionType.EXPENSE, "mcc_5499"),
                tx(200.0, TransactionType.EXPENSE, "mcc_5499"),
                tx(150.0, TransactionType.EXPENSE, "mcc_5541")
            )
        )

        assertEquals(listOf("Imported"), result.income.map { it.label })
        assertEquals(8_000.0, result.totalIncome, 0.0)

        // Two grocery rows collapse into one slice, and the biggest amount leads.
        assertEquals(listOf("Groceries", "Fuel"), result.expense.map { it.label })
        assertEquals(650.0, result.totalExpense, 0.0)
        assertEquals(500.0, result.expense.single { it.label == "Groceries" }.amount, 0.0)
        assertEquals(150.0, result.expense.single { it.label == "Fuel" }.amount, 0.0)
    }

    @Test
    fun `an income and expense row of the same category stay on separate sides`() {
        val result = of(
            listOf(
                tx(100.0, TransactionType.INCOME, "mcc_5499"),
                tx(100.0, TransactionType.EXPENSE, "mcc_5499")
            )
        )

        assertEquals(listOf("Groceries"), result.income.map { it.label })
        assertEquals(listOf("Groceries"), result.expense.map { it.label })
        assertEquals(100.0, result.totalIncome, 0.0)
        assertEquals(100.0, result.totalExpense, 0.0)
    }

    @Test
    fun `converts each currency to the base before summing`() {
        val result = of(
            listOf(
                tx(1_000.0, TransactionType.INCOME, "imported", "UAH"),
                tx(300.0, TransactionType.INCOME, "imported", "USD")
            )
        )

        assertEquals(1, result.income.size)
        assertEquals("Imported", result.income.single().label)
        assertEquals(13_000.0, result.totalIncome, 0.0)
        assertTrue(result.expense.isEmpty())
        assertTrue(result.unquoted.isEmpty())
    }

    @Test
    fun `transfers are excluded from both sides`() {
        val result = of(
            listOf(
                tx(100.0, TransactionType.INCOME, "imported"),
                tx(5_000.0, TransactionType.TRANSFER, "investments", "UAH", TransferDirection.OUT)
            )
        )

        assertEquals(100.0, result.totalIncome, 0.0)
        assertEquals(0.0, result.totalExpense, 0.0)
        assertEquals(listOf("Imported"), result.income.map { it.label })
    }

    @Test
    fun `an unquotable base yields no breakdown at all`() {
        val plnRates = ExchangeRates(
            toUah = mapOf("USD" to 40.0),
            date = "01.10.2026",
            fetchedAt = 0L
        )
        val result = categoryTotals(
            listOf(tx(100.0, TransactionType.INCOME, "imported", "UAH")),
            plnRates,
            "PLN",
            { CategoryLabel.label(it) }
        )

        assertFalse(result.isBaseQuotable)
        assertTrue(result.income.isEmpty())
        assertEquals(listOf<String?>("PLN"), result.unquoted)
    }

    @Test
    fun `an unquotable currency is named and left out while the base still adds up`() {
        val result = of(
            listOf(
                tx(1_000.0, TransactionType.INCOME, "imported", "UAH"),
                tx(500.0, TransactionType.EXPENSE, "mcc_5499", "GBP"),
                tx(200.0, TransactionType.EXPENSE, "mcc_5499", "UAH")
            )
        )

        assertEquals(listOf<String?>("GBP"), result.unquoted)
        assertEquals(1_000.0, result.totalIncome, 0.0)
        assertEquals(200.0, result.totalExpense, 0.0)
        // Only the UAH grocery row lands in the breakdown; GBP was dropped.
        assertTrue(result.expense.all { it.label == "Groceries" })
    }

    @Test
    fun `a row with no currency is named as a missing code, not folded into a real one`() {
        val result = of(
            listOf(
                tx(100.0, TransactionType.EXPENSE, "mcc_5499", "UAH"),
                tx(70.0, TransactionType.EXPENSE, "imported", null)
            )
        )

        assertEquals(1, result.unquoted.size)
        assertTrue(result.unquoted[0] == null)
        assertEquals(100.0, result.totalExpense, 0.0)
    }

    @Test
    fun `percentages of a side sum to 100`() {
        val result = of(
            listOf(
                tx(300.0, TransactionType.EXPENSE, "mcc_5499"),
                tx(200.0, TransactionType.EXPENSE, "mcc_5541"),
                tx(500.0, TransactionType.EXPENSE, "imported")
            )
        )

        assertTrue(result.income.isEmpty())
        assertEquals(100.0, result.expense.sumOf { it.percent }, 1e-9)
    }

    @Test
    fun `slices are ordered largest first`() {
        val result = of(
            listOf(
                tx(100.0, TransactionType.EXPENSE, "mcc_5541"),
                tx(500.0, TransactionType.EXPENSE, "imported"),
                tx(200.0, TransactionType.EXPENSE, "mcc_5499")
            )
        )

        assertEquals(listOf("Imported", "Groceries", "Fuel"), result.expense.map { it.label })
    }


    @Test
    fun `an unquotable currency is collected once even if several rows miss it`() {
        val result = of(
            listOf(
                tx(200.0, TransactionType.EXPENSE, "mcc_5499", "GBP"),
                tx(300.0, TransactionType.EXPENSE, "mcc_5499", "GBP"),
                tx(100.0, TransactionType.INCOME, "imported", "UAH")
            )
        )

        // Both GBP rows are dropped, but GBP is named once, not per row.
        assertEquals(listOf<String?>("GBP"), result.unquoted)
        assertEquals(100.0, result.totalIncome, 0.0)
        assertEquals(0.0, result.totalExpense, 0.0)
    }

    @Test
    fun `displaySlices keeps every slice when the set fits the cap`() {
        val slices = listOf(
            CategorySlice("A", 60.0, 0.0),
            CategorySlice("B", 30.0, 0.0),
            CategorySlice("C", 5.0, 0.0),
            CategorySlice("D", 2.0, 0.0),
            CategorySlice("E", 3.0, 0.0)
        )

        val displayed = displaySlices(slices, otherLabel = "Other")

        // Nothing is folded away: a small slice is a real category and stays its own row.
        assertEquals(listOf("A", "B", "C", "E", "D"), displayed.map { it.label })
        assertEquals(100, displayed.sumOf { it.percent.toInt() })
    }

    @Test
    fun `displaySlices rounds with the largest remainder so the total is exactly 100`() {
        val slices = listOf(
            CategorySlice("A", 333.0, 0.0),
            CategorySlice("B", 333.0, 0.0),
            CategorySlice("C", 334.0, 0.0) // 33.4%, the only slice that rounds up
        )

        val displayed = displaySlices(slices, otherLabel = "Other")

        assertEquals(100, displayed.sumOf { it.percent.toInt() })
        // C (33.4%) is the largest and absorbs the one leftover unit.
        assertEquals(listOf(34, 33, 33), displayed.map { it.percent.toInt() })
    }

    @Test
    fun `displaySlices keeps a set of small slices rather than hiding them behind Other`() {
        // Six equal slices of ~16.7%: none is large, but none is folded either — a dominant
        // Other must not make a whole side of small categories vanish.
        val slices = (1..6).map { CategorySlice("c$it", 1.0, 0.0) }

        val displayed = displaySlices(slices, otherLabel = "Other")

        assertEquals(6, displayed.size)
        assertEquals(100, displayed.sumOf { it.percent.toInt() })
        assertTrue(displayed.none { it.label == "Other" })
    }

    @Test
    fun `displaySlices caps a long tail into Other so the ring stays readable`() {
        // 25 equal slices: past a handful the eye cannot match a wedge to a legend row, so the
        // largest six are kept and the other nineteen fold into one Other, which is then the
        // biggest row and leads the sorted result.
        val slices = (1..25).map { CategorySlice("c$it", 1.0, 0.0) }

        val displayed = displaySlices(slices, otherLabel = "Other")

        assertEquals(7, displayed.size)
        assertEquals("Other", displayed.first().label)
        assertEquals(19.0, displayed.first().amount, 0.0)
        assertEquals(100, displayed.sumOf { it.percent.toInt() })
    }

    @Test
    fun `displaySlices folds the capped overflow into a real Other rather than duplicating it`() {
        // A real Other is the largest and therefore among the kept; the long tail must merge
        // into it, not append a second row with the same label. Six rows are kept (Other plus
        // the five largest small ones), and the five-slice overflow lands inside Other.
        val slices = listOf(CategorySlice("Other", 50.0, 0.0)) +
            (1..10).map { CategorySlice("c$it", 5.0, 0.0) }

        val displayed = displaySlices(slices, otherLabel = "Other")

        assertEquals(6, displayed.size)
        assertEquals(1, displayed.count { it.label == "Other" })
        assertEquals(75.0, displayed.single { it.label == "Other" }.amount, 0.0)
        assertEquals(100, displayed.sumOf { it.percent.toInt() })
    }

    @Test
    fun `displaySlices keeps a lone slice at 100`() {
        val displayed = displaySlices(listOf(CategorySlice("Only", 100.0, 0.0)), "Other")

        assertEquals(1, displayed.size)
        assertEquals(100, displayed.single().percent.toInt())
    }

    @Test
    fun `displaySlices keeps small real categories beside Other when the set fits the cap`() {
        // A dominant real Other plus two small categories: under the cap nothing is folded, so
        // the small ones stay visible beside Other instead of disappearing into it.
        val slices = listOf(
            CategorySlice("Other", 94.0, 0.0),
            CategorySlice("Small", 3.0, 0.0),
            CategorySlice("Tiny", 3.0, 0.0)
        )

        val displayed = displaySlices(slices, otherLabel = "Other")

        assertEquals(3, displayed.size)
        assertEquals(1, displayed.count { it.label == "Other" })
        assertEquals(100, displayed.sumOf { it.percent.toInt() })
    }
}


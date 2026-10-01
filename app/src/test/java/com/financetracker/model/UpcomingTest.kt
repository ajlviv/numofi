package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The projection: which occurrences fall in a window, and what they add up to once converted.
 *
 * The two things that matter are that expense and income are kept apart, and that a currency the
 * cache cannot quote is excluded *and named* rather than silently dropped — the same rule the
 * net-worth total follows.
 */
class UpcomingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")

    // NBU's USD figure for the day this was designed against, so the converted numbers are
    // checkable by hand.
    private val rates = ExchangeRates(toUah = mapOf("USD" to 44.0), date = "01.10.2026", fetchedAt = 0L)
    private val noRates = ExchangeRates(toUah = emptyMap(), date = null, fetchedAt = 0L)

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun definition(
        id: Long,
        amount: Double,
        type: TransactionType = TransactionType.EXPENSE,
        currency: String = "UAH",
        start: LocalDate,
        end: LocalDate? = null,
        archived: Boolean = false
    ) = RecurringPayment(
        id = id,
        title = "Test $id",
        amount = amount,
        type = type,
        category = "other",
        currencyCode = currency,
        frequency = RepeatFrequency.MONTHLY,
        intervalCount = 1,
        startDate = millis(start),
        endDate = end?.let { millis(it) },
        archived = archived
    )

    private fun project(
        definitions: List<RecurringPayment>,
        from: LocalDate,
        to: LocalDate,
        rates: ExchangeRates = this.rates,
        base: String = "UAH"
    ) = upcoming(definitions, rates, base, from, to, zone)

    @Test
    fun `scheduled expense and income convert into the base and stay apart`() {
        val result = project(
            listOf(
                definition(1, amount = 1_000.0, start = LocalDate.of(2026, 1, 5)),
                definition(
                    2, amount = 100.0, type = TransactionType.INCOME,
                    currency = "USD", start = LocalDate.of(2026, 1, 20)
                )
            ),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1)
        )

        assertEquals(1_000.0, result.expense!!, 1e-9)
        assertEquals(4_400.0, result.income!!, 1e-9)
        assertTrue(result.unquoted.isEmpty())
        assertEquals(2, result.items.size)
    }

    @Test
    fun `a currency the cache cannot quote is excluded and named`() {
        val result = project(
            listOf(
                definition(1, amount = 1_000.0, start = LocalDate.of(2026, 1, 5)),
                definition(
                    2, amount = 100.0, type = TransactionType.INCOME,
                    currency = "USD", start = LocalDate.of(2026, 1, 20)
                )
            ),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1),
            rates = noRates
        )

        // The hryvnia half still totals; the dollar half is out of the figure and named.
        assertEquals(1_000.0, result.expense!!, 1e-9)
        assertEquals(0.0, result.income!!, 1e-9)
        assertEquals(listOf("USD"), result.unquoted)
        // Nothing is hidden: both occurrences are still listed, the dollar one in dollars.
        assertEquals(2, result.items.size)
    }

    @Test
    fun `an archived definition is not projected`() {
        val result = project(
            listOf(definition(1, amount = 1_000.0, start = LocalDate.of(2026, 1, 5), archived = true)),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1)
        )

        assertTrue(result.items.isEmpty())
        assertEquals(0.0, result.expense!!, 1e-9)
    }

    @Test
    fun `a schedule that ends before the window contributes nothing`() {
        val result = project(
            listOf(
                definition(
                    id = 1, amount = 500.0,
                    start = LocalDate.of(2025, 1, 1),
                    end = LocalDate.of(2025, 12, 1)
                )
            ),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1)
        )

        assertTrue(result.items.isEmpty())
        assertEquals(0.0, result.expense!!, 1e-9)
    }

    @Test
    fun `occurrences are listed oldest first`() {
        val result = project(
            listOf(
                definition(1, amount = 100.0, start = LocalDate.of(2026, 1, 20)),
                definition(2, amount = 200.0, start = LocalDate.of(2026, 1, 5))
            ),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1)
        )

        assertEquals(
            listOf(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 20)),
            result.items.map { it.date }
        )
    }

    @Test
    fun `no definitions is zeroes rather than a missing figure`() {
        val result = project(emptyList(), from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 2, 1))

        assertEquals(0.0, result.expense!!, 1e-9)
        assertEquals(0.0, result.income!!, 1e-9)
        assertTrue(result.unquoted.isEmpty())
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `a base the cache cannot quote is no figure at all, not zero`() {
        val result = project(
            listOf(definition(1, amount = 1_000.0, start = LocalDate.of(2026, 1, 5))),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1),
            rates = noRates,
            base = "USD"
        )

        assertNull(result.expense)
        assertNull(result.income)
        assertEquals(listOf("USD"), result.unquoted)
        // The list is still complete, so an unreadable total cannot hide what it left out.
        assertEquals(1, result.items.size)
    }
}

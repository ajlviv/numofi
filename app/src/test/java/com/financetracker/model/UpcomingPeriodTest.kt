package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The four windows the plans screen offers, as half-open `[from, to)` ranges.
 *
 * The property the tests exist for is that the ranges are half-open and adjacent: a period's
 * `to` is the next period's `from`, so a day belongs to exactly one of two neighbours and the
 * boundary is never counted twice. A month is reached by `withDayOfMonth(1).plusMonths(1)`,
 * never by adding days, so a 31-day month cannot move a month-start.
 */
class UpcomingPeriodTest {

    private fun range(period: UpcomingPeriod, today: String) =
        period.range(LocalDate.parse(today))

    @Test
    fun `next 30 days runs from today to today plus 30`() {
        val (from, to) = range(UpcomingPeriod.NEXT_30_DAYS, "2026-01-15")
        assertEquals(LocalDate.of(2026, 1, 15), from)
        assertEquals(LocalDate.of(2026, 2, 14), to)
    }

    @Test
    fun `next month is the calendar month after this one`() {
        val (from, to) = range(UpcomingPeriod.NEXT_MONTH, "2026-01-15")
        assertEquals(LocalDate.of(2026, 2, 1), from)
        assertEquals(LocalDate.of(2026, 3, 1), to)
    }

    @Test
    fun `next 12 months is a rolling year from today`() {
        val (from, to) = range(UpcomingPeriod.NEXT_12_MONTHS, "2026-01-15")
        assertEquals(LocalDate.of(2026, 1, 15), from)
        assertEquals(LocalDate.of(2027, 1, 15), to)
    }

    @Test
    fun `this year is the calendar year, not the year remaining`() {
        val (from, to) = range(UpcomingPeriod.THIS_YEAR, "2026-01-15")
        assertEquals(LocalDate.of(2026, 1, 1), from)
        assertEquals(LocalDate.of(2027, 1, 1), to)
    }

    @Test
    fun `the rolling year and the calendar year are different windows`() {
        val today = LocalDate.of(2026, 6, 15)
        assertTrue(
            UpcomingPeriod.NEXT_12_MONTHS.range(today) != UpcomingPeriod.THIS_YEAR.range(today)
        )
    }

    @Test
    fun `a 31-day month does not move the month start`() {
        val (from, to) = range(UpcomingPeriod.NEXT_MONTH, "2026-01-31")
        assertEquals(LocalDate.of(2026, 2, 1), from)
        assertEquals(LocalDate.of(2026, 3, 1), to)
    }

    @Test
    fun `december rolls into the next year`() {
        val (from, to) = range(UpcomingPeriod.NEXT_MONTH, "2026-12-20")
        assertEquals(LocalDate.of(2027, 1, 1), from)
        assertEquals(LocalDate.of(2027, 2, 1), to)
    }

    @Test
    fun `a leap year February keeps the month boundary`() {
        val (from, to) = range(UpcomingPeriod.NEXT_MONTH, "2028-01-10")
        assertEquals(LocalDate.of(2028, 2, 1), from)
        assertEquals(LocalDate.of(2028, 3, 1), to)
    }

    @Test
    fun `every range is non-empty`() {
        val today = LocalDate.of(2026, 1, 15)
        UpcomingPeriod.entries.forEach { period ->
            val (from, to) = period.range(today)
            assertTrue("$period is empty", from < to)
        }
    }
}

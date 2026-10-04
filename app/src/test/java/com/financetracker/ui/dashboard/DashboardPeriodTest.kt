package com.financetracker.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pinning the wall-clock dependency so month-start and 90-day math is deterministic.
 *
 * A 2026-02-15 date is used because February is the short month that trips the "stepped
 * instead of anchored" recurrence bug — the same reason the AGENTS.md recurrence invariant
 * calls out 31st-of-month drift. Here it guards window edges instead: the 90-day window
 * must not be computed by subtracting a month from "now" stepwise.
 */
class DashboardPeriodTest {

    private val zone = ZoneOffset.UTC
    // 2026-02-15 12:00:00 UTC
    private val nowMillis = Instant.parse("2026-02-15T12:00:00Z").toEpochMilli()
    private val feb1 = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
    private val feb14End = Instant.parse("2026-02-14T23:59:59.999Z").toEpochMilli()

    @Test
    fun `ALL_TIME includes everything`() {
        assertTrue(DashboardPeriod.ALL_TIME.contains(0L, nowMillis, zone))
        assertTrue(DashboardPeriod.ALL_TIME.contains(nowMillis, nowMillis, zone))
        assertTrue(DashboardPeriod.ALL_TIME.contains(Long.MAX_VALUE, nowMillis, zone))
    }

    @Test
    fun `ALL_TIME window is null`() {
        assertEquals(null, DashboardPeriod.ALL_TIME.window(nowMillis, zone))
    }

    @Test
    fun `month-to-date starts at the first millisecond of the current month`() {
        val w = DashboardPeriod.MONTH_TO_DATE.window(nowMillis, zone)!!
        assertEquals(feb1, w.from)
        assertEquals(nowMillis, w.to)
    }

    @Test
    fun `month-to-date is half-open the start is in the boundary is out`() {
        // Half-open [from, to): Feb 1 00:00 is the start and is included, and the instant of
        // `now` itself is the exclusive upper bound and is not.
        assertTrue(DashboardPeriod.MONTH_TO_DATE.contains(feb1, nowMillis, zone))
        assertTrue(DashboardPeriod.MONTH_TO_DATE.contains(feb1 + 1, nowMillis, zone))
        assertTrue(DashboardPeriod.MONTH_TO_DATE.contains(feb14End, nowMillis, zone))
        assertTrue(DashboardPeriod.MONTH_TO_DATE.contains(nowMillis - 1, nowMillis, zone))
        assertFalse(DashboardPeriod.MONTH_TO_DATE.contains(nowMillis, nowMillis, zone))
    }

    @Test
    fun `month-to-date excludes the last day of the previous month`() {
        // 2026-01-31 23:59:59.999 — exactly the millisecond before Feb 1 in UTC.
        val jan31End = Instant.parse("2026-01-31T23:59:59.999Z").toEpochMilli()
        assertFalse(DashboardPeriod.MONTH_TO_DATE.contains(jan31End, nowMillis, zone))
    }

    @Test
    fun `90-day window is 90 days not three calendar months`() {
        val w = DashboardPeriod.LAST_90_DAYS.window(nowMillis, zone)!!
        assertEquals(90 * DashboardPeriod.DAY_MS, w.to - w.from)
    }

    @Test
    fun `90-day window is half-open`() {
        val w = DashboardPeriod.LAST_90_DAYS.window(nowMillis, zone)!!
        assertTrue(DashboardPeriod.LAST_90_DAYS.contains(w.from, nowMillis, zone))
        assertTrue(DashboardPeriod.LAST_90_DAYS.contains(w.to - 1, nowMillis, zone))
        assertFalse(DashboardPeriod.LAST_90_DAYS.contains(w.to, nowMillis, zone))
    }

    @Test
    fun `90-day window excludes the day before its start`() {
        // 90 days before Feb 15 12:00 UTC is Nov 17 12:00 UTC. Nov 17 is the inclusive start,
        // so it is in; Nov 16 is the first excluded day.
        val nov17 = Instant.parse("2025-11-17T12:00:00Z").toEpochMilli()
        val nov16 = Instant.parse("2025-11-16T12:00:00Z").toEpochMilli()
        val w = DashboardPeriod.LAST_90_DAYS.window(nowMillis, zone)!!
        assertEquals(nov17, w.from)
        assertTrue(DashboardPeriod.LAST_90_DAYS.contains(nov17, nowMillis, zone))
        assertFalse(DashboardPeriod.LAST_90_DAYS.contains(nov16, nowMillis, zone))
    }

    @Test
    fun `short month does not drift the window`() {
        // If the window were computed by subtracting a month stepwise from now (mid-Feb),
        // Feb 15 → Jan 15 → Dec 15 → Oct 15 would give the wrong start. Anchoring to
        // nowMillis - 90*DAY_MS yields the same date regardless of which short months it crosses.
        val w = DashboardPeriod.LAST_90_DAYS.window(nowMillis, zone)!!
        assertEquals(nowMillis - 90 * DashboardPeriod.DAY_MS, w.from)
    }

    @Test
    fun `this-year starts at the first millisecond of the current year`() {
        val w = DashboardPeriod.THIS_YEAR.window(nowMillis, zone)!!
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), w.from)
        assertEquals(nowMillis, w.to)
    }

    @Test
    fun `this-year is half-open the start is in the boundary is out`() {
        val jan1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertTrue(DashboardPeriod.THIS_YEAR.contains(jan1, nowMillis, zone))
        assertTrue(DashboardPeriod.THIS_YEAR.contains(nowMillis - 1, nowMillis, zone))
        assertFalse(DashboardPeriod.THIS_YEAR.contains(nowMillis, nowMillis, zone))
    }

    @Test
    fun `this-year excludes the last millisecond of the previous year`() {
        val dec31End = Instant.parse("2025-12-31T23:59:59.999Z").toEpochMilli()
        assertFalse(DashboardPeriod.THIS_YEAR.contains(dec31End, nowMillis, zone))
    }

    @Test
    fun `this-year covers a whole leap year when asked on its last day`() {
        // 2024 is a leap year, so its last day is the 366th. Anchoring to day-of-year 1 has to
        // land on Jan 1 rather than on day 365, which a `minusDays(365)` shortcut would do.
        val lastMomentOfLeapYear = Instant.parse("2024-12-31T23:59:59Z").toEpochMilli()
        val w = DashboardPeriod.THIS_YEAR.window(lastMomentOfLeapYear, zone)!!
        assertEquals(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(), w.from)
        assertTrue(DashboardPeriod.THIS_YEAR.contains(Instant.parse("2024-02-29T12:00:00Z").toEpochMilli(), lastMomentOfLeapYear, zone))
    }

    @Test
    fun `this-year always contains month-to-date`() {
        // The selector offers overlapping windows; switching to a wider one must never appear
        // to lose rows. Month-to-date is nested in this-year on every day of the year, because
        // the 1st of this month is never earlier than the 1st of January.
        assertTrue(
            DashboardPeriod.MONTH_TO_DATE.window(nowMillis, zone)!!.from >=
                DashboardPeriod.THIS_YEAR.window(nowMillis, zone)!!.from
        )
    }

    @Test
    fun `this-year is narrower than 90 days early in the year and wider late in it`() {
        // The two windows cross: on 15 Feb, 90 days back reaches into the previous year, so
        // LAST_90_DAYS starts earlier and covers more. From March onwards the year boundary
        // overtakes it and THIS_YEAR becomes the wider of the two. Neither is a superset of the
        // other all year, which is why this is pinned rather than assumed.
        val earlyInYear = Instant.parse("2026-02-15T12:00:00Z").toEpochMilli()
        val lateInYear = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()

        assertTrue(
            DashboardPeriod.THIS_YEAR.window(earlyInYear, zone)!!.from >
                DashboardPeriod.LAST_90_DAYS.window(earlyInYear, zone)!!.from
        )
        assertTrue(
            DashboardPeriod.THIS_YEAR.window(lateInYear, zone)!!.from <
                DashboardPeriod.LAST_90_DAYS.window(lateInYear, zone)!!.from
        )
    }
}

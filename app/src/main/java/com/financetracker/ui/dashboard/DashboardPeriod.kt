package com.financetracker.ui.dashboard

import androidx.annotation.StringRes
import com.financetracker.R
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Time windows for the dashboard's income and expense flows.
 *
 * The headline net-worth total is a stock: the current balance plus bond nominal,
 * independent of any window. Income and spent are flows, and so they can be scoped to one.
 * A window is half-open `[from, to)`, matching the transaction-date filter, so two adjacent
 * windows never both claim a day.
 *
 * `ALL_TIME` is the default and matches the previous behaviour exactly: no row is ever
 * filtered, so existing users see nothing change on first open.
 *
 * The window is computed from [nowMillis], which every function defaults to the wall clock
 * so call sites read `period.contains(row.timestamp)`. Tests pass an explicit instant to keep
 * the month-start arithmetic deterministic regardless of when the suite runs.
 */
enum class DashboardPeriod(@StringRes val labelRes: Int) {
    ALL_TIME(R.string.dash_period_all_time),
    MONTH_TO_DATE(R.string.dash_period_mtd),
    LAST_90_DAYS(R.string.dash_period_90d),
    THIS_YEAR(R.string.dash_period_year);

    /**
     * Half-open millisecond bounds `[from, to)` at [nowMillis], or null for no window.
     */
    fun window(nowMillis: Long, zone: ZoneId): TimeWindow? = when (this) {
        ALL_TIME -> null
        MONTH_TO_DATE -> TimeWindow(monthStart(nowMillis, zone), nowMillis)
        LAST_90_DAYS -> TimeWindow(nowMillis - DAY_MS * 90, nowMillis)
        THIS_YEAR -> TimeWindow(yearStart(nowMillis, zone), nowMillis)
    }

    /**
     * Whether [timestamp] falls in this period's window at [nowMillis].
     */
    fun contains(
        timestamp: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean =
        window(nowMillis, zone)?.contains(timestamp) ?: true

    private fun monthStart(nowMillis: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            .withDayOfMonth(1)
            .withHour(0)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()

    private fun yearStart(nowMillis: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            .withDayOfYear(1)
            .withHour(0)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()

    companion object {
        const val DAY_MS = 86_400_000L
    }
}

/** A half-open `[from, to)` span of epoch milliseconds. */
data class TimeWindow(val from: Long, val to: Long) {
    fun contains(timestamp: Long) = timestamp >= from && timestamp < to
}



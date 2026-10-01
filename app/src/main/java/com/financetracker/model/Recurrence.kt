package com.financetracker.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How often a recurring payment repeats.
 *
 * Deliberately three values and no more. A calendar engine — daily, "the 2nd Tuesday", skips,
 * per-occurrence overrides — is where a feature like this stops being small, and none of those
 * cases is common enough to be worth that. [Recurrence] covers the rest with an interval:
 * every two weeks is `WEEKLY` × 2, a quarter is `MONTHLY` × 3.
 */
enum class RepeatFrequency {
    WEEKLY,
    MONTHLY,
    YEARLY
}

/**
 * The dates a recurring payment falls on inside a window.
 *
 * Plain functions over plain values, like [BondMath], so the walk can be checked against a
 * calendar by hand rather than through a screen. Two rules carry the whole thing:
 *
 * ## The n-th occurrence is computed from the anchor, never by stepping
 *
 * Each date is `anchor + (n × interval)`, not `previous + interval`. Stepping repeatedly drifts
 * at month ends: 31 January plus one month is 28 February, and *that* plus one month is 28
 * March — the series has silently moved off the 31st for good. Computing from the anchor gives
 * 31 January, 28 February, 31 March: the clamp is a property of each occurrence, not a
 * cumulative error. `LocalDate.plusMonths` already clamps to the last valid day, so the calendar
 * does the hard part and this rule is the part that is worth a test.
 *
 * ## The window is half-open, `[from, to)`
 *
 * The same convention the transaction list's date filter uses, so a day is in exactly one
 * window and two adjacent windows never both claim it. [RecurringPayment.startDate] and
 * `endDate` are inclusive, because a user entering a schedule names the day it starts, not the
 * instant after it.
 */
object Recurrence {

    /**
     * Every occurrence in `[from, to)`, oldest first, or an empty list when there is none.
     *
     * [startDateMillis] and [endDateMillis] are epoch millis read as local dates in [zone], the
     * same reading [com.financetracker.model.TransactionInstant] gives a stored timestamp.
     * [endDateMillis] is inclusive; null means open-ended, in which case [to] is the only limit
     * and the series cannot run away.
     */
    fun occurrencesBetween(
        frequency: RepeatFrequency,
        intervalCount: Int,
        startDateMillis: Long,
        endDateMillis: Long?,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId
    ): List<LocalDate> {
        // A zero or negative interval is not a schedule, and multiplying by it would loop on
        // one date forever. Refused here rather than trusted from the caller.
        if (intervalCount < 1) return emptyList()

        val anchor = localDate(startDateMillis, zone)
        val lastDay = endDateMillis?.let { localDate(it, zone) }
        val windowEnd = to.minusDays(1)
        if (windowEnd.isBefore(from)) return emptyList()

        val occurrences = mutableListOf<LocalDate>()
        var n = 0L
        while (true) {
            val date = advance(anchor, frequency, n * intervalCount)
            // Dates only increase with n, so the first one past either bound ends the walk.
            if (date.isAfter(windowEnd)) break
            if (lastDay != null && date.isAfter(lastDay)) break
            if (!date.isBefore(from)) occurrences += date
            n++
        }
        return occurrences
    }

    /**
     * The first occurrence on or after [from], or null when the schedule has none — either it
     * already ended, or [intervalCount] is not a schedule.
     *
     * The same walk as [occurrencesBetween] with no upper bound, kept separate rather than
     * expressed as a window of one day: a definition whose next date is months away still has a
     * next date, and asking for tomorrow's window would report none.
     */
    fun nextOccurrence(
        frequency: RepeatFrequency,
        intervalCount: Int,
        startDateMillis: Long,
        endDateMillis: Long?,
        from: LocalDate,
        zone: ZoneId
    ): LocalDate? {
        if (intervalCount < 1) return null

        val anchor = localDate(startDateMillis, zone)
        val lastDay = endDateMillis?.let { localDate(it, zone) }

        var n = 0L
        while (true) {
            val date = advance(anchor, frequency, n * intervalCount)
            if (lastDay != null && date.isAfter(lastDay)) return null
            if (!date.isBefore(from)) return date
            n++
        }
    }

    /**
     * [anchor] advanced by [steps] whole periods.
     *
     * One place that knows which calendar field a frequency moves, so adding a frequency later
     * is a case here and nothing else. [steps] is already `n × intervalCount`.
     */
    private fun advance(anchor: LocalDate, frequency: RepeatFrequency, steps: Long): LocalDate =
        when (frequency) {
            RepeatFrequency.WEEKLY -> anchor.plusWeeks(steps)
            RepeatFrequency.MONTHLY -> anchor.plusMonths(steps)
            RepeatFrequency.YEARLY -> anchor.plusYears(steps)
        }

    /** The calendar date an instant falls on, as [zone] reads it. */
    private fun localDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}

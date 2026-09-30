package com.financetracker.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The instant a hand-entered transaction is stamped with.
 *
 * ## Why this is not simply midnight
 *
 * The transaction list is ordered by `timestamp DESC, id DESC`, and the two ways a row gets
 * into the database disagree about what a timestamp means. A statement line carries the bank's
 * own time of day, so an import of today is stamped at the moment the purchase was reported.
 * A typed entry has no time at all — the form asks for a date — and stamping it at midnight
 * therefore made it claim the purchase happened at 00:00, which put every import of the day
 * above a transaction the user had added a moment earlier and made a list that reads
 * backwards.
 *
 * `id DESC` already breaks a tie between two rows stamped identically, so the tiebreak was
 * never the problem: the timestamps genuinely differed, and the more recent row had the
 * smaller one.
 *
 * ## The rule
 *
 * A day the user is filling in *now* is stamped with the time they filled it in, because that
 * is the one thing known about it and it is the truthful reading. Any other day is stamped at
 * its start, because the hour of entry says nothing about a day already past or not yet
 * arrived, and inventing one would be a claim the app has no basis for.
 */
object TransactionInstant {

    /**
     * [date] as an epoch millisecond, on [zone], at the moment [enteredAt] was recorded.
     *
     * Always inside [date]'s own local day, which is what the day headings are derived from —
     * including on a day whose midnight does not exist locally, where the clocks go forward
     * over it.
     */
    fun forEnteredDate(date: LocalDate, enteredAt: LocalDateTime, zone: ZoneId): Long {
        val local = if (date == enteredAt.toLocalDate()) {
            // Today's entry, so today's hour. Taken from the date rather than reused whole, so
            // a `LocalDateTime` carrying a different day cannot drag the row off the one chosen.
            LocalDateTime.of(date, enteredAt.toLocalTime())
        } else {
            date.atStartOfDay()
        }
        return local.atZone(zone).toInstant().toEpochMilli()
    }
}

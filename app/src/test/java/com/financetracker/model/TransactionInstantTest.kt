package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What instant a hand-entered transaction is stamped with.
 *
 * The list is ordered by `timestamp DESC, id DESC`, and the two write paths do not agree
 * about what a timestamp means: an imported statement line carries the bank's time of day,
 * while a typed entry could only be stamped at the start of its day. That put every import of
 * today above a transaction added a moment ago, which is not what either of those timestamps
 * was trying to say.
 */
class TransactionInstantTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")

    private fun at(instant: Long, zone: ZoneId = kyiv) =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(instant), zone)

    @Test
    fun `a row entered for today keeps the time it was entered`() {
        val enteredAt = LocalDateTime.of(2026, 9, 30, 14, 23)

        val instant = TransactionInstant.forEnteredDate(enteredAt.toLocalDate(), enteredAt, kyiv)

        assertEquals(enteredAt, at(instant).toLocalDateTime())
    }

    @Test
    fun `a row entered for today sorts below an import from earlier the same day`() {
        val today = LocalDate.of(2026, 9, 30)
        // What a statement line for 09:15 looks like: a real time of day, not midnight.
        val importedEarlierToday = today.atTime(9, 15).atZone(kyiv).toInstant().toEpochMilli()

        val instant = TransactionInstant.forEnteredDate(today, today.atTime(14, 23), kyiv)

        // The reported symptom, stated as an ordering: `ORDER BY timestamp DESC` puts the
        // larger one first, and "added just now" has to be the larger one.
        assertTrue(
            "an entry made at 14:23 must sort above an import from 09:15",
            instant > importedEarlierToday
        )
    }

    @Test
    fun `two rows entered on the same day are ordered by when they were entered`() {
        val today = LocalDate.of(2026, 9, 30)

        val first = TransactionInstant.forEnteredDate(today, today.atTime(9, 0), kyiv)
        val second = TransactionInstant.forEnteredDate(today, today.atTime(17, 45), kyiv)

        assertTrue(second > first)
    }

    @Test
    fun `a row entered for a past day keeps the start of that day`() {
        val enteredAt = LocalDateTime.of(2026, 9, 30, 14, 23)
        val backdated = LocalDate.of(2026, 8, 2)

        val instant = TransactionInstant.forEnteredDate(backdated, enteredAt, kyiv)

        // Backdated to a day already past, the hour of entry says nothing about when the
        // purchase happened, and inventing one would be a claim the app has no basis for. The
        // start of the day is the honest reading and groups it under the right heading.
        assertEquals(backdated.atStartOfDay(), at(instant).toLocalDateTime())
    }

    @Test
    fun `a row entered for a future day also keeps the start of that day`() {
        val enteredAt = LocalDateTime.of(2026, 9, 30, 14, 23)
        val future = LocalDate.of(2026, 10, 1)

        val instant = TransactionInstant.forEnteredDate(future, enteredAt, kyiv)

        assertEquals(future.atStartOfDay(), at(instant).toLocalDateTime())
    }

    @Test
    fun `the instant always lands inside the day that was chosen`() {
        // Day headings are derived from the timestamp, so an entry that fell into the
        // neighbouring day would be filed under the wrong heading while looking correct. Some
        // zones skip midnight entirely when the clocks go forward, which is where a naive
        // `atStartOfDay` is most likely to put a row on the wrong day.
        val santiago = ZoneId.of("America/Santiago")
        for (day in 1..28) {
            for (date in listOf(LocalDate.of(2026, 9, day), LocalDate.of(2026, 4, day))) {
                val times = listOf(
                    date.atStartOfDay(),
                    date.atTime(14, 23),
                    date.atTime(23, 59)
                )
                for (time in times) {
                    val instant = TransactionInstant.forEnteredDate(date, time, santiago)
                    assertEquals(
                        "$date at $time landed on ${at(instant, santiago).toLocalDate()}",
                        date,
                        at(instant, santiago).toLocalDate()
                    )
                }
            }
        }
    }
}

package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * The occurrence walk, checked against a calendar rather than through a screen.
 *
 * The risk this file exists for is drift at month ends: a series that steps from the previous
 * occurrence loses the 31st after the first short month, and nothing on screen would say so.
 */
class RecurrenceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun between(
        frequency: RepeatFrequency,
        interval: Int,
        start: LocalDate,
        end: LocalDate? = null,
        from: LocalDate,
        to: LocalDate
    ) = Recurrence.occurrencesBetween(
        frequency = frequency,
        intervalCount = interval,
        startDateMillis = millis(start),
        endDateMillis = end?.let { millis(it) },
        from = from,
        to = to,
        zone = zone
    )

    @Test
    fun `a monthly schedule on the 31st clamps each short month and recovers`() {
        // The drift this whole rule exists for: stepping Feb 28 by a month lands on Mar 28.
        val occurrences = between(
            RepeatFrequency.MONTHLY, 1,
            start = LocalDate.of(2026, 1, 31),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 5, 1)
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30)
            ),
            occurrences
        )
    }

    @Test
    fun `a yearly schedule on a leap day clamps and recovers`() {
        val occurrences = between(
            RepeatFrequency.YEARLY, 1,
            start = LocalDate.of(2024, 2, 29),
            from = LocalDate.of(2024, 1, 1),
            to = LocalDate.of(2027, 1, 1)
        )

        assertEquals(
            listOf(
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2025, 2, 28),
                LocalDate.of(2026, 2, 28)
            ),
            occurrences
        )
    }

    @Test
    fun `an interval of two weeks gives every other week`() {
        val occurrences = between(
            RepeatFrequency.WEEKLY, 2,
            start = LocalDate.of(2026, 3, 2),
            from = LocalDate.of(2026, 3, 1),
            to = LocalDate.of(2026, 4, 1)
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 16),
                LocalDate.of(2026, 3, 30)
            ),
            occurrences
        )
    }

    @Test
    fun `a quarterly schedule is a monthly one at an interval of three`() {
        val occurrences = between(
            RepeatFrequency.MONTHLY, 3,
            start = LocalDate.of(2026, 1, 15),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 8, 1)
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 4, 15),
                LocalDate.of(2026, 7, 15)
            ),
            occurrences
        )
    }

    @Test
    fun `the end date is inclusive and stops the walk`() {
        val occurrences = between(
            RepeatFrequency.MONTHLY, 1,
            start = LocalDate.of(2026, 1, 10),
            end = LocalDate.of(2026, 3, 10),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 12, 31)
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 2, 10),
                LocalDate.of(2026, 3, 10)
            ),
            occurrences
        )
    }

    @Test
    fun `a start in the future yields nothing before it but something after`() {
        val occurrences = between(
            RepeatFrequency.MONTHLY, 1,
            start = LocalDate.of(2026, 6, 1),
            from = LocalDate.of(2026, 5, 1),
            to = LocalDate.of(2026, 8, 1)
        )

        assertEquals(
            listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)),
            occurrences
        )
    }

    @Test
    fun `the window is half-open so the end day belongs to the next one`() {
        val occurrences = between(
            RepeatFrequency.MONTHLY, 1,
            start = LocalDate.of(2026, 1, 1),
            // to is exclusive: 1 February is not in this window even though it falls on the day.
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1)
        )

        assertEquals(listOf(LocalDate.of(2026, 1, 1)), occurrences)
    }

    @Test
    fun `a window that ends before the start is empty`() {
        val occurrences = between(
            RepeatFrequency.MONTHLY, 1,
            start = LocalDate.of(2026, 6, 1),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 2, 1)
        )

        assertTrue(occurrences.isEmpty())
    }

    @Test
    fun `a weekly series keeps its weekday across a daylight-saving change`() {
        // Kyiv moves the clocks on 2026-03-29. Occurrences are calendar dates, so the anchor's
        // weekday has to survive the shift rather than drifting by an hour's worth of arithmetic.
        val occurrences = between(
            RepeatFrequency.WEEKLY, 1,
            start = LocalDate.of(2026, 3, 22),
            from = LocalDate.of(2026, 3, 20),
            to = LocalDate.of(2026, 4, 13)
        )

        assertEquals(4, occurrences.size)
        occurrences.forEach { assertEquals(DayOfWeek.SUNDAY, it.dayOfWeek) }
        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 22),
                LocalDate.of(2026, 3, 29),
                LocalDate.of(2026, 4, 5),
                LocalDate.of(2026, 4, 12)
            ),
            occurrences
        )
    }

    @Test
    fun `an interval below one is not a schedule`() {
        assertTrue(
            between(
                RepeatFrequency.MONTHLY, 0,
                start = LocalDate.of(2026, 1, 1),
                from = LocalDate.of(2026, 1, 1),
                to = LocalDate.of(2027, 1, 1)
            ).isEmpty()
        )
    }

    @Test
    fun `the next occurrence counts a day the window includes`() {
        val next = Recurrence.nextOccurrence(
            frequency = RepeatFrequency.MONTHLY,
            intervalCount = 1,
            startDateMillis = millis(LocalDate.of(2026, 1, 10)),
            endDateMillis = null,
            from = LocalDate.of(2026, 2, 10),
            zone = zone
        )

        assertEquals(LocalDate.of(2026, 2, 10), next)
    }

    @Test
    fun `the next occurrence is null once the schedule has ended`() {
        val next = Recurrence.nextOccurrence(
            frequency = RepeatFrequency.MONTHLY,
            intervalCount = 1,
            startDateMillis = millis(LocalDate.of(2026, 1, 10)),
            endDateMillis = millis(LocalDate.of(2026, 3, 10)),
            from = LocalDate.of(2026, 6, 1),
            zone = zone
        )

        assertNull(next)
    }
}

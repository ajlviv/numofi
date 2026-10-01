package com.financetracker.ui.recurring

import com.financetracker.model.RepeatFrequency
import com.financetracker.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The form's conversion into a stored schedule.
 *
 * Checked as plain business logic rather than through a ViewModel, and the reason is worth
 * stating: the form offers no control over the archived flag, so it has to be *carried* through
 * [RecurringForm] rather than re-derived. Dropping it would quietly un-archive a schedule on
 * every save, and nothing on screen would say so — a bug no UI test here would catch either.
 */
class RecurringFormTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")

    private fun form(archived: Boolean = false, note: String? = null) = RecurringForm(
        title = "  Rent  ",
        amount = 12_000.0,
        type = TransactionType.EXPENSE,
        category = "other",
        currencyCode = "UAH",
        bankCode = null,
        note = note,
        frequency = RepeatFrequency.MONTHLY,
        intervalCount = 1,
        startDate = LocalDate.of(2026, 1, 31),
        endDate = null,
        archived = archived
    )

    @Test
    fun `an archived schedule stays archived through a save`() {
        val payment = form(archived = true).toPayment(id = 7L, zone = zone)

        assertTrue(payment.archived)
        assertEquals(7L, payment.id)
    }

    @Test
    fun `a live schedule does not become archived`() {
        assertFalse(form().toPayment(id = null, zone = zone).archived)
    }

    @Test
    fun `the title is trimmed, the note carried, and the date read as a local day`() {
        val payment = form(note = "Промбут").toPayment(id = null, zone = zone)

        assertEquals("Rent", payment.title)
        assertEquals("Промбут", payment.note)
        assertEquals(
            LocalDate.of(2026, 1, 31),
            Instant.ofEpochMilli(payment.startDate).atZone(zone).toLocalDate()
        )
    }
}

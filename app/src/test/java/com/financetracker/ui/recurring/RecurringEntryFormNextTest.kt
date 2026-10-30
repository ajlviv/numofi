package com.financetracker.ui.recurring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financetracker.R
import com.financetracker.model.Recurrence
import com.financetracker.model.RepeatFrequency
import com.financetracker.model.RecurringPayment
import com.financetracker.model.TransactionType
import com.financetracker.util.DateFormats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The "Next" preview inside the plan edit form, and that it is derived rather than printed.
 *
 * The form used to show only its inputs, so changing the start recalculated nothing the user
 * could see until after the save — the one moment the adjustment matters most was the one
 * moment it was invisible. Two assertions cover what would make that regression possible: the
 * line must come from the *form's* start (not a fixed guess), and it must be recomputed when a
 * field that feeds it changes.
 *
 * The screen is made tall enough to hold the whole form, so every node below is on screen and
 * none of the assertions depend on scroll choreography — they are about the derivation, not
 * about where the field sits.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w320dp-h1000dp")
class RecurringEntryFormNextTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Far enough in the past that the walk finds the next occurrence, not the anchor itself. */
    private val start = LocalDate.of(2026, 1, 5)

    private val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()

    private val existing = RecurringPayment(
        id = 7L,
        title = "Rent",
        amount = 12_000.0,
        type = TransactionType.EXPENSE,
        category = "other",
        currencyCode = "UAH",
        frequency = RepeatFrequency.MONTHLY,
        intervalCount = 1,
        startDate = startMillis,
        endDate = null
    )

    @Test
    fun theNextLineIsTheOccurrenceDerivedFromTheStart() {
        show()

        compose.onNodeWithText(nextLine(RepeatFrequency.MONTHLY)).assertIsDisplayed()
    }

    @Test
    fun theNextLineRecalculatesWhenAFieldThatFeedsItChanges() {
        show()

        // Weekly and monthly walks over the same anchor land on different dates, so the line
        // changing proves the figure was recomputed from the field rather than fixed once.
        compose.onNodeWithText(string(R.string.recurring_freq_monthly)).performClick()
        compose.onNodeWithText(string(R.string.recurring_freq_weekly)).performClick()

        compose.onNodeWithText(nextLine(RepeatFrequency.WEEKLY)).assertIsDisplayed()
    }

    private fun show() {
        compose.setContent {
            RecurringEntryForm(
                existing = existing,
                banks = emptyList(),
                categories = emptyList(),
                onSave = {},
                onCancel = {}
            )
        }
    }

    /** The preview exactly as the form renders it: the same walk, the same format, the same key. */
    private fun nextLine(frequency: RepeatFrequency): String {
        val next = Recurrence.nextOccurrence(
            frequency = frequency,
            intervalCount = 1,
            startDateMillis = startMillis,
            endDateMillis = null,
            from = LocalDate.now(zone),
            zone = zone
        )!!
        return string(R.string.recurring_next_due, next.format(DateFormats.date()))
    }

    private fun string(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}

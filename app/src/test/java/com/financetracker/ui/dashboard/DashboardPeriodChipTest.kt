package com.financetracker.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financetracker.R
import com.financetracker.model.ExchangeRates
import com.financetracker.model.ExclusionRules
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every period the selector offers has to be reachable, not merely present.
 *
 * The selector is a row of chips whose combined width is the sum of four translated labels plus
 * a FilterChip's padding and its 48dp minimum touch target. A plain `Row` clips whatever does not
 * fit, and adding a fourth option is what pushed it over: the last chip sat past the right edge,
 * so a period the app computed, translated and tested was a period nobody could select. Nothing
 * threw — the flows it filters simply never appeared, which is the quietest possible failure for
 * a feature whose whole content is "show me a different range of the same numbers".
 *
 * Hence the narrow-width cases below. They are the ones that bite, and they are also why the
 * default-width cases here are worth keeping: a chip row can lose this again silently the next
 * time a period is added, and asserting on a label being *present* would not notice, because a
 * clipped chip is still in the tree.
 */
@RunWith(AndroidJUnit4::class)
class DashboardPeriodChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val rates = ExchangeRates(toUah = mapOf("USD" to 40.0), date = "01.10.2026", fetchedAt = 0L)

    private val transaction = Transaction(
        id = 1L,
        title = "Salary",
        amount = 100.0,
        type = TransactionType.INCOME,
        category = "imported",
        timestamp = 0L,
        currencyCode = "UAH"
    )

    private fun show() {
        compose.setContent {
            DashboardScreen(
                transactions = listOf(transaction),
                counted = null,
                rules = ExclusionRules(),
                positions = emptyList(),
                baseCurrency = "UAH",
                rates = rates,
                onTransactionClick = {}
            )
        }
    }

    @Test
    fun everyOfferedPeriodIsReachable() {
        show()

        // Scrolled to, not merely queried: a chip clipped past the edge of a fixed row is still
        // in the semantics tree, so onNodeWithText finds it either way and the assertion above
        // the defect would pass straight over it.
        DashboardPeriod.entries.forEach { period ->
            compose.onNodeWithText(label(period)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w200dp-h640dp")
    fun everyOfferedPeriodIsReachableOnANarrowScreen() {
        show()

        DashboardPeriod.entries.forEach { period ->
            compose.onNodeWithText(label(period)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w200dp-h640dp")
    fun theLastOfferedPeriodCanBeSelectedOnANarrowScreen() {
        show()

        // LAST_90_DAYS is last in declaration order, so it is the one a clipping row drops.
        val last = DashboardPeriod.entries.last()

        compose.onNodeWithText(label(last)).performScrollTo().assertIsDisplayed().performClick()

        compose.onNodeWithText(label(last)).assertIsSelected()
        compose.onNodeWithText(label(DashboardPeriod.ALL_TIME)).assertIsNotSelected()
    }

    @Test
    fun selectingAPeriodReportsItAsWindowed() {
        show()

        val thisYear = DashboardPeriod.THIS_YEAR
        compose.onNodeWithText(label(thisYear)).performScrollTo().performClick()

        // The caption is what tells the user the figures below are scoped rather than
        // cumulative, so a period that selected but left the caption off would be putting a
        // number on screen it is not standing behind.
        compose.onNodeWithText(context.getString(R.string.dash_flow_window)).assertIsDisplayed()
        compose.onNodeWithText(label(thisYear)).assertIsSelected()
    }

    @Test
    fun allTimeStaysSelectedUntilAPeriodIsChosen() {
        show()

        compose.onNodeWithText(label(DashboardPeriod.ALL_TIME)).assertIsSelected()

        compose.onNodeWithText(label(DashboardPeriod.THIS_YEAR))
            .performScrollTo()
            .performClick()

        compose.onNodeWithText(label(DashboardPeriod.ALL_TIME)).assertIsNotSelected()
    }

    private fun label(period: DashboardPeriod) = context.getString(period.labelRes)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
}

package com.financetracker.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financetracker.model.ExchangeRates
import com.financetracker.model.ExclusionRules
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The period selector, and that every window it offers can be reached and chosen.
 *
 * It used to be a row of chips, and adding a fourth option pushed the last one past the right
 * edge of a narrow screen: a period the app computed, translated and tested was a period nobody
 * could select. It is a dropdown now, whose menu holds every option in one place, so that
 * clipping failure is gone — but the assertions that a window is present, reachable and updates
 * the control are still worth keeping, because a period dropped from the menu would be just as
 * unreachable as one clipped off the edge.
 */
@RunWith(AndroidJUnit4::class)
class DashboardPeriodSelectorTest {

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
    fun theSelectorStartsOnAllTime() {
        show()

        // The button carries the current period, so which window the flows are scoped to is
        // readable without opening the menu.
        compose.onNodeWithText(label(DashboardPeriod.ALL_TIME)).assertIsDisplayed()
    }

    @Test
    fun everyOfferedPeriodIsReachable() {
        show()

        // Opened once, because a dropdown's options do not exist in the tree until it is.
        // ALL_TIME is skipped while open: it is the button's own label too, so asserting on it
        // would see two nodes rather than the option being reached for.
        compose.onNodeWithText(label(DashboardPeriod.ALL_TIME)).performClick()

        DashboardPeriod.entries
            .filter { it != DashboardPeriod.ALL_TIME }
            .forEach { period ->
                compose.onNodeWithText(label(period)).assertIsDisplayed()
            }
    }

    @Test
    @Config(qualifiers = "w200dp-h640dp")
    fun everyOfferedPeriodIsReachableOnANarrowScreen() {
        show()

        compose.onNodeWithText(label(DashboardPeriod.ALL_TIME)).performClick()

        DashboardPeriod.entries
            .filter { it != DashboardPeriod.ALL_TIME }
            .forEach { period ->
                compose.onNodeWithText(label(period)).assertIsDisplayed()
            }
    }

    @Test
    fun choosingAPeriodUpdatesTheSelector() {
        show()

        val thisYear = DashboardPeriod.THIS_YEAR
        compose.onNodeWithText(label(DashboardPeriod.ALL_TIME)).performClick()
        compose.onNodeWithText(label(thisYear)).performClick()

        // The menu has closed and the button now reads the choice, which is the same job the
        // selected chip used to do.
        compose.onNodeWithText(label(thisYear)).assertIsDisplayed()
    }

    private fun label(period: DashboardPeriod) = context.getString(period.labelRes)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
}

package com.financetracker.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financetracker.model.Bond
import com.financetracker.model.BondPosition
import com.financetracker.model.ExchangeRates
import com.financetracker.model.ExclusionRules
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the dashboard says about a holding.
 *
 * The figure on the bond card is quantity × the last price the user entered, not nominal, while
 * the net worth headline above it counts the same holding at nominal. The two disagree by the
 * discount to par plus fees, so these tests pin which reading each figure is: the headline must
 * stay at nominal, and the card must show the marked value rather than quietly agreeing with it.
 */
@RunWith(AndroidJUnit4::class)
class DashboardBondCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val rates = ExchangeRates(
        toUah = mapOf("USD" to 40.0, "EUR" to 50.0),
        date = "01.10.2026",
        fetchedAt = 0L
    )

    /**
     * One ОВДП bought at par and marked above it, so nominal, cost and market value are three
     * different numbers. Distinct on purpose: with cost equal to the marked value the two appear
     * on the card as the same string, and a test cannot tell which one it found.
     */
    private fun position(
        name: String = "ОВДП 26/Б",
        nominal: Double = 1_000.0,
        quantity: Int = 20,
        price: Double = 1_010.0,
        boughtAt: Double = 1_000.0
    ) = BondPosition(
        bond = Bond(isin = "UA0000000001", name = name, nominal = nominal, nominalCurrency = "UAH"),
        quantity = quantity,
        cost = quantity * boughtAt,
        averageCost = boughtAt,
        lastPrice = price,
        annualCouponIncome = quantity * nominal * 0.175,
        heldSince = 0L
    )

    private fun show(
        positions: List<BondPosition>,
        transactions: List<Transaction> = emptyList(),
        rules: ExclusionRules = ExclusionRules(),
        onRefresh: () -> Unit = {}
    ) {
        compose.setContent {
            DashboardScreen(
                transactions = transactions,
                counted = null,
                rules = rules,
                positions = positions,
                baseCurrency = "UAH",
                rates = rates,
                onRefreshRates = onRefresh,
                onTransactionClick = {}
            )
        }
    }

    @Test
    fun theCardShowsTheMarkedValueNotTheNominal() {
        // No transactions, so the figure appears once. The Recent list would otherwise carry
        // the same amount from the purchase row and a substring match would find both.
        show(listOf(position()))

        // 20 × 1010 = 20 200, where nominal would be 20 000. The card is deliberately the
        // market reading; this test says so out loud, so that a change to nominal here fails
        // rather than passing unnoticed as "roughly right".
        // Once, because cost is 20 000 here and the marked value is the only 20 200 on screen.
        assertEquals(1, compose.onAllNodesWithText("20,200.00", substring = true).fetchSemanticsNodes().size)

        // And 20 000 does appear — as the cost, and as the net worth headline, which is at
        // nominal by design. That the two readings coexist on one screen is the whole reason
        // the card needs to say which of them it is showing.
        assertEquals(2, compose.onAllNodesWithText("20,000.00", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun netWorthCountsTheHoldingAtNominalNotAtTheMarkedValue() {
        val purchase = Transaction(
            id = 1L,
            title = "ОВДП 26/Б",
            amount = 20_200.0,
            type = TransactionType.TRANSFER,
            category = "investments",
            timestamp = 0L,
            currencyCode = "UAH",
            transferDirection = TransferDirection.OUT
        )
        show(listOf(position()), transactions = listOf(purchase))

        // Cash is 0 − 20 200, and the holding adds 20 000 of nominal, so −200. Counting the
        // marked value instead would give −400: the headline must not be the card's figure.
        compose.onNodeWithText("-200.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun aHoldingTheRulesRemoveIsNamedRatherThanSilentlyDropped() {
        val rules = ExclusionRules(listOf("ОВДП"))
        val purchase = Transaction(
            id = 1L,
            title = "ОВДП 26/Б",
            amount = 20_200.0,
            type = TransactionType.TRANSFER,
            category = "investments",
            timestamp = 0L,
            currencyCode = "UAH",
            transferDirection = TransferDirection.OUT
        )

        show(listOf(position()), rules = rules)

        // No rows at all, so the only thing a rule could remove is the holding itself — which is
        // the case where cash and bonds must agree. The holding is named, because a total that
        // quietly drops part of itself looks exactly like a total that has nothing to drop.
        compose.onNodeWithText("1 holding(s)", substring = true).assertIsDisplayed()
    }

    @Test
    fun rowsWithNoCurrencyAreNamedRatherThanGuessedAt() {
        val noCode = Transaction(
            id = 1L,
            title = "Mystery",
            amount = 70.0,
            type = TransactionType.EXPENSE,
            category = "imported",
            timestamp = 0L,
            currencyCode = null
        )

        show(listOf(position()), transactions = listOf(noCode))

        compose.onNodeWithText("rows with no currency", substring = true).assertIsDisplayed()
    }

    @Test
    fun thePeriodSelectorOffersTheWindowsAndTheHeadlineStaysAllTime() {
        val purchase = Transaction(
            id = 1L,
            title = "ОВДП 26/Б",
            amount = 20_200.0,
            type = TransactionType.TRANSFER,
            category = "investments",
            timestamp = 0L,
            currencyCode = "UAH",
            transferDirection = TransferDirection.OUT
        )
        show(listOf(position()), transactions = listOf(purchase))

        // The button carries the current period; the rest are in the menu it opens.
        compose.onNodeWithText("All time").performClick()
        compose.onNodeWithText("This month").assertIsDisplayed()
        compose.onNodeWithText("Last 90 days").assertIsDisplayed()

        // The headline is the all-time figure whatever the selector says. A windowed total with
        // no bonds in it would not be a net worth, so the selector moves the flows and not this.
        compose.onNodeWithText("-200.00", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Last 90 days").performClick()

        compose.onNodeWithText("-200.00", substring = true).assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("-200.00", substring = true).fetchSemanticsNodes().size)
    }
}
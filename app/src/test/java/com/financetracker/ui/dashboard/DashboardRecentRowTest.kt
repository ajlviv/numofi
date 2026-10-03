package com.financetracker.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * The five most recent rows on the dashboard, and what happens when one is tapped.
 *
 * These rows were the only list in the app that could not be opened. Everywhere else a
 * transaction is one tap from its detail screen, where the type can be overruled — so the type
 * picker was reachable only by going to the Transactions tab and finding the row there, which
 * is a longer path than the row being on screen already invites.
 *
 * A row that looks like every other row and does nothing is worse than no row: the alternative
 * was a summary list with no affordance at all, and the fix is to make the promise true rather
 * than to remove the promise.
 */
@RunWith(AndroidJUnit4::class)
class DashboardRecentRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val rates = ExchangeRates(toUah = mapOf("USD" to 40.0), date = "01.10.2026", fetchedAt = 0L)

    private fun transaction(title: String, type: TransactionType = TransactionType.EXPENSE) =
        Transaction(
            id = title.hashCode().toLong(),
            title = title,
            amount = 100.0,
            type = type,
            category = "imported",
            timestamp = 0L,
            currencyCode = "UAH",
            transferDirection = if (type == TransactionType.TRANSFER) TransferDirection.OUT else null
        )

    private fun show(rows: List<Transaction>, onTransactionClick: (Transaction) -> Unit = {}) {
        compose.setContent {
            DashboardScreen(
                transactions = rows,
                counted = null,
                rules = ExclusionRules(),
                positions = emptyList(),
                baseCurrency = "UAH",
                rates = rates,
                onTransactionClick = onTransactionClick
            )
        }
    }

    @Test
    fun tappingARecentRowOpensIt() {
        var opened: Transaction? = null
        val rows = listOf(transaction("Salary"), transaction("Groceries"))
        show(rows) { opened = it }

        // The row is what carries the click, not the text inside it, so the matcher follows
        // the row that contains the title. Tapping the text node itself would be a test that
        // passes or fails for reasons that have nothing to do with what a user touches.
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Groceries")),
            useUnmergedTree = true
        ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

        assertEquals("Groceries", opened?.title)
    }

    @Test
    fun theRowTappedIsTheOneTapped() {
        val opened = mutableListOf<String>()
        show(listOf(transaction("Salary"), transaction("Groceries"), transaction("Fuel"))) {
            opened += it.title
        }

        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Fuel")),
            useUnmergedTree = true
        ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

        // Not merely "something opened". Two rows of the same shape, and the wrong one being
        // reported would make the whole list unusable for its purpose.
        assertEquals(listOf("Fuel"), opened)
    }

    @Test
    fun recentRowsAreListedWhenThereAreTransactions() {
        show(listOf(transaction("Salary")))

        compose.onNodeWithText("Recent").assertIsDisplayed()
        compose.onNodeWithText("Salary").assertIsDisplayed()
    }
}
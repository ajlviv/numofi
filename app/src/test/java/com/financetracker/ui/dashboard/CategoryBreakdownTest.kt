package com.financetracker.ui.dashboard

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
// import androidx.compose.ui.test.assertDoesNotExist — member function, no import needed (see TransactionRowTest.kt)
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financetracker.model.CategorySlice
import com.financetracker.model.CategoryTotals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryBreakdownTest {

    @get:Rule
    val compose = createComposeRule()

    // 300 Groceries + 200 Fuel, 1 000 Income. Whole percentages that sum to 100 on each side,
    // so the assertions below do not depend on rounding luck.
    private val breakdown = CategoryTotals(
        base = "UAH",
        income = listOf(CategorySlice("Imported", 1_000.0, 100.0)),
        expense = listOf(
            CategorySlice("Groceries", 300.0, 60.0),
            CategorySlice("Fuel", 200.0, 40.0)
        ),
        totalIncome = 1_000.0,
        totalExpense = 500.0,
        unquoted = emptyList(),
        isBaseQuotable = true
    )

    private fun show(breakdown: CategoryTotals = this.breakdown) {
        compose.setContent { CategoryBreakdownCard(breakdown) }
    }

    @Test
    fun titlesTheCardAndOffersBothSides() {
        show()
        compose.onNodeWithText("By category").assertIsDisplayed()
        compose.onNodeWithText("Spent").assertIsDisplayed()
        compose.onNodeWithText("Income").assertIsDisplayed()
    }

    @Test
    fun theSpentBreakdownListsCategoriesWithAmountsAndShares() {
        show()
        compose.onNodeWithText("Groceries").assertIsDisplayed()
        compose.onNodeWithText("60%").assertIsDisplayed()
        compose.onNodeWithText("300.00", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Fuel").assertIsDisplayed()
        compose.onNodeWithText("40%").assertIsDisplayed()
        compose.onNodeWithText("200.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun togglingToIncomeReplacesTheLegend() {
        show()
        compose.onNodeWithText("Income").performClick()

        compose.onNodeWithText("Imported").assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
        // The spent categories are gone from the legend.
        compose.onNodeWithText("Groceries").assertDoesNotExist()
    }

    @Test
    fun togglingBackRestoresTheSpentLegend() {
        show()
        compose.onNodeWithText("Income").performClick()
        compose.onNodeWithText("Spent").performClick()

        compose.onNodeWithText("Groceries").assertIsDisplayed()
        compose.onNodeWithText("60%").assertIsDisplayed()
    }

    @Test
    fun anEmptySideExplainsThereIsNothingToShow() {
        val empty = CategoryTotals(
            base = "UAH",
            income = emptyList(),
            expense = emptyList(),
            totalIncome = 0.0,
            totalExpense = 0.0,
            unquoted = emptyList(),
            isBaseQuotable = true
        )
        show(empty)

        // Default tab is Spent; with nothing spent there is a plain message and no legend.
        compose.onNodeWithText("No spending in this period").assertIsDisplayed()
        compose.onNodeWithText("Groceries").assertDoesNotExist()
    }

    @Test
    fun anUnquotableBaseHidesTheCard() {
        // The card delegates to the net-worth card for the explanation; it renders nothing here.
        show(CategoryTotals.unquotable("PLN"))

        compose.onNodeWithText("By category").assertDoesNotExist()
        compose.onNodeWithText("Spent").assertDoesNotExist()
    }

    @Test
    fun theCardNamesTheWindowItCovers() {
        compose.setContent { CategoryBreakdownCard(breakdown, periodLabel = "This month") }
        compose.onNodeWithText("This month", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingALegendRowNamesTheSliceInTheRing() {
        show()
        // The share lives once in the legend at rest; picking the row moves the same figure
        // into the ring's hole, so a finger that highlighted a wedge also reads the number.
        compose.onNodeWithText("Fuel").performClick()

        compose.onAllNodesWithText("40%").assertCountEquals(2)
    }
}

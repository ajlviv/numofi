package com.financetracker.ui.transaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionKind
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Overruling the one field on a transaction the app inferred rather than read.
 *
 * A bank says a movement left an account; it does not say whether that was spending, income, or
 * money moving to an account the user also owns. The app has to guess, and this screen is where
 * the guess is corrected — so it is the only control in the app that can change a type.
 *
 * It is here on the JVM under Robolectric rather than on a device because this is precisely the
 * screen that could not be driven by hand: it is reached through a tap, and the badge that opens
 * the picker is a small target in a long list. Driving it in a test is both easier and
 * repeatable, and it means a change to the badge or the dialog fails a build rather than waiting
 * to be noticed.
 */
@RunWith(AndroidJUnit4::class)
class TransactionDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun expense() = Transaction(
        id = 1L,
        title = "Оплата товарів",
        amount = 731.80,
        type = TransactionType.EXPENSE,
        category = "imported",
        timestamp = 0L,
        currencyCode = "UAH"
    )

    private fun show(transaction: Transaction, onTypeChange: (TransactionKind) -> Unit = {}) {
        compose.setContent {
            TransactionDetailScreen(
                transaction = transaction,
                bankNames = emptyMap(),
                onBack = {},
                onDelete = {},
                onTypeChange = onTypeChange
            )
        }
    }

    @Test
    fun theBadgeShowsTheStoredTypeAndOpensThePickerWhenTapped() {
        show(expense())

        // The badge is the affordance. If it ever stops being tappable the only way to correct a
        // type is gone, and nothing else on the screen would look broken.
        compose.onNodeWithText("Expense").performClick()

        compose.onNodeWithText("Change type").assertIsDisplayed()
    }

    @Test
    fun everyKindTheModelDefinesIsOffered() {
        show(expense())
        compose.onNodeWithText("Expense").performClick()

        // The four kinds are the four the model defines, so no combination shown here can mean
        // nothing — in particular no transfer without a direction, which would say nothing about
        // which way the money went.
        compose.onNodeWithText("Income").assertIsDisplayed()
        compose.onNodeWithText("Transfer received").assertIsDisplayed()
        compose.onNodeWithText("Transfer sent").assertIsDisplayed()
        // "Expense" appears twice, and both are wanted: the badge behind the dialog and the
        // option in it. Asserting the count rather than a single match is what keeps this test
        // honest — `onNodeWithText` would fail on the ambiguity rather than quietly pass.
        assertEquals(2, compose.onAllNodesWithText("Expense").fetchSemanticsNodes().size)
    }

    @Test
    fun choosingAKindReportsItAndClosesThePicker() {
        var chosen: TransactionKind? = null
        show(expense()) { chosen = it }

        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Transfer received").performClick()

        assertEquals(TransactionKind.TRANSFER_IN, chosen)
        compose.onNodeWithText("Change type").assertDoesNotExist()
    }

    @Test
    fun cancellingLeavesTheTypeAlone() {
        var chosen: TransactionKind? = null
        show(expense()) { chosen = it }

        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Cancel").performClick()

        // Nothing chosen is the point of a cancel: the app's guess stands unless overruled.
        assertNull(chosen)
        compose.onNodeWithText("Change type").assertDoesNotExist()
    }

    @Test
    fun theAmountSignFollowsTheStoredType() {
        // A transfer in is money arriving, so it reads positive where an expense read negative.
        // Getting this backwards would show a user being paid money as though they paid it.
        show(
            expense().copy(
                type = TransactionType.TRANSFER,
                transferDirection = TransferDirection.IN
            )
        )

        compose.onNodeWithText("+731.80", substring = true).assertIsDisplayed()
    }

    @Test
    fun theBadgeShowsATransferWithoutItsDirection() {
        // The bare word, because on this screen the direction is not yet decided: the badge
        // offers to change it, and saying "received" here would claim a choice the user has not
        // made.
        show(
            expense().copy(
                type = TransactionType.TRANSFER,
                transferDirection = TransferDirection.OUT
            )
        )

        compose.onNodeWithText("Transfer").assertIsDisplayed()
    }
}
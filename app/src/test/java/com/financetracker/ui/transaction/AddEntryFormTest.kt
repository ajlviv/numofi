package com.financetracker.ui.transaction

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financetracker.model.BankRef
import com.financetracker.model.BondTradeSide
import com.financetracker.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Filling in and saving the transaction form.
 *
 * These two forms were the largest untested surface in the app: every row verified so far was
 * written straight into SQLite, so nothing had ever proved that the path a user actually takes
 * — typing a title, typing an amount, pressing the button — reaches a save at all. A save
 * button that silently did nothing would have passed every other test in the project.
 *
 * The forms are driven directly rather than through the screen that hosts them. That screen takes
 * its state from a ViewModel, and this project treats ViewModels as thin wiring with no tests of
 * their own; the logic worth testing is the form's own — what counts as valid, what the amount
 * parser makes of what was typed, and what the save is handed.
 *
 * Clicks go through [SemanticsActions.OnClick] rather than `performClick`: an injected touch
 * lands on nothing under Robolectric, so a test using it can pass without the handler running.
 */
@RunWith(AndroidJUnit4::class)
class AddEntryFormTest {

    @get:Rule
    val compose = createComposeRule()

    private val banks = listOf(BankRef("UA_MBO", "Monobank"))

    /**
     * Presses the button whose whole content is [label].
     *
     * `onNodeWithText` alone is ambiguous on the bond form: "Buy" is both a side in the
     * segmented control and the label on the button that saves a purchase. The exact match is
     * what tells them apart, and an ambiguous matcher would fail rather than silently press the
     * wrong one.
     */
    private fun press(label: String) {
        compose.onNode(hasText(label) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * The save button, told apart from the side selector by what each one is.
     *
     * Both say "Buy" — one is the choice of side, the other the button that records it — so
     * the label cannot tell them apart and role can. Asking for a Button is also the honest
     * description of what is being pressed.
     */
    private fun pressSave(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and hasTestTagOrRole("Button"))
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun hasTestTagOrRole(role: String): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)
            .and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

    /** Fills a field by its label, which is what the label is for. */
    private fun fill(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label, substring = true))
            .performTextClearance()
        compose.onNode(hasSetTextAction() and hasText(label, substring = true))
            .performTextInput(value)
    }

    // MARK: - The transaction form

    private fun showForm(onSave: (TransactionForm) -> Unit = {}) {
        compose.setContent {
            TransactionForm(
                banks = banks,
                noBankLabel = "No bank",
                saving = false,
                onSave = onSave
            )
        }
    }

    @Test
    fun aTransactionIsSavedWithTheTitleAndAmountTyped() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        fill("Title", "Salary")
        fill("Amount", "25000")
        press("Add Transaction")

        assertEquals("Salary", saved?.title)
        assertEquals(25_000.0, saved?.amount ?: 0.0, 0.0)
    }

    @Test
    fun aCommaDecimalIsAcceptedRatherThanRefused() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        // The reason AmountInput exists. A Ukrainian keypad sends a comma on the decimal key,
        // and before it the form refused the value outright.
        fill("Title", "Salary")
        fill("Amount", "25000,50")
        press("Add Transaction")

        assertEquals(25_000.5, saved?.amount ?: 0.0, 1e-9)
    }

    @Test
    fun nothingIsSavedWithoutATitle() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        fill("Amount", "100")
        press("Add Transaction")

        // A row with no title is one the user cannot find again, and the list shows the title
        // as the primary line — so an untitled row is a row that reads as blank.
        assertNull("a titleless row must not save", saved)
        compose.onNodeWithText("Enter a title").assertExists()
    }

    @Test
    fun aZeroAmountIsRefusedWithTheAmountFieldMarkedInError() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        fill("Title", "Refund")
        fill("Amount", "0")
        press("Add Transaction")

        assertNull("a zero amount must not save", saved)
        // The message and the outline have to agree. An error under a field with no red
        // outline tells the reader the value is wrong while the field insists it is fine.
        compose.onNodeWithText("Enter an amount greater than zero").assertExists()
    }

    @Test
    fun aNegativeAmountIsRefused() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        fill("Title", "Refund")
        fill("Amount", "-100")
        press("Add Transaction")

        assertNull("a negative amount must not save", saved)
    }

    @Test
    fun theTypeCanBeChosenBeforeSaving() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        fill("Title", "Shop")
        fill("Amount", "100")
        press("Expense")
        press("Add Transaction")

        assertEquals(TransactionType.EXPENSE, saved?.type)
    }

    @Test
    fun aTransactionDefaultsToIncome() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        fill("Title", "Shop")
        fill("Amount", "100")
        press("Add Transaction")

        // Stated so a change of default is a deliberate act rather than a surprise.
        assertEquals(TransactionType.INCOME, saved?.type)
    }

    // MARK: - The bond form

    private fun showBondForm(onSave: (BondForm) -> Unit = {}) {
        compose.setContent {
            BondEntryForm(
                banks = banks,
                noBankLabel = "No bank",
                saving = false,
                onLookup = {},
                knownBond = null,
                onSave = onSave
            )
        }
    }

    @Test
    fun aBondPurchaseIsSavedWithTheFiguresTyped() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010")
        pressSave("Buy")

        assertEquals(20, saved?.quantity)
        assertEquals(1010.0, saved?.price ?: 0.0, 0.0)
        assertEquals("ОВДП 26/Б", saved?.bond?.name)
        // Nominal is the face of one bond, which is how a broker quotes it. The 20 000 is the
        // position, and that multiplication happens where the holding is folded.
        assertEquals(1_000.0, saved?.bond?.nominal ?: 0.0, 0.0)
    }

    @Test
    fun aBondPurchaseIsNotSavedWithoutAName() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        // An ОВДП with no name would be a position the user cannot identify later.
        fill("Quantity", "20")
        fill("Price/bond", "1010")
        pressSave("Buy")

        assertNull("a nameless bond must not save", saved)
        compose.onNodeWithText("Enter a name").assertExists()
    }

    @Test
    fun aBondPurchaseIsNotSavedWithoutAQuantity() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Price/bond", "1010")
        pressSave("Buy")

        // Zero bonds bought is not a trade, and a position of zero would sit in the holdings
        // list contributing nothing and costing a row of the list to read past.
        assertNull(saved)
    }

    @Test
    fun aZeroPriceIsRefused() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "0")
        pressSave("Buy")

        // A price of zero would record a purchase of nothing for nothing, and the cost basis it
        // produces would be nonsense for every later sale.
        assertNull(saved)
    }

    @Test
    fun theSideChoosesWhichTradeIsBeingRecorded() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010")
        press("Sell")
        pressSave("Sell")

        assertEquals(BondTradeSide.SELL, saved?.side)
    }

    @Test
    fun aCommaDecimalIsAcceptedForAPrice() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010,25")
        pressSave("Buy")

        assertEquals(1010.25, saved?.price ?: 0.0, 1e-9)
    }

    @Test
    fun anUnreadableAccruedInterestIsRefusedRatherThanRecordedAsZero() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010")
        fill("Accrued / bond", "abc")
        pressSave("Buy")

        // The field used to coerce anything it could not read to zero, so a slip in the accrued
        // figure was recorded as a purchase with no accrued interest — understating the cost of
        // every bond in the position, quietly, and never shown again. A blank box is still fine;
        // a box holding something unreadable is not.
        assertNull("unreadable accrued must not save", saved)
        compose.onNodeWithText("Could not read the accrued interest").assertExists()
    }

    @Test
    fun anUnreadableCommissionIsRefusedRatherThanRecordedAsZero() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010")
        fill("Commission", "12,50,50")
        pressSave("Buy")

        // The same coercion, and the same silent understatement of what the trade cost. The
        // input here is one the parser refuses rather than misreads: a mistyped box used to be
        // read as 125 050 of commission.
        assertNull("unreadable commission must not save", saved)
        compose.onNodeWithText("Could not read the commission").assertExists()
    }

    @Test
    fun blankAccruedAndCommissionStillMeanZero() {
        var saved: BondForm? = null
        showBondForm { saved = it }

        // Both fields are optional. An empty box is a decision — there was no accrued interest
        // and no fee — and refusing it would make the common trade impossible to enter.
        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010")
        pressSave("Buy")

        assertEquals(0.0, saved?.accrued ?: -1.0, 1e-9)
        assertEquals(0.0, saved?.commission ?: -1.0, 1e-9)
    }

    @Test
    fun anUnreadableAccruedInterestStillAllowsAFieldThatIsLeftEmpty() {
        // The counterpart, so the refusal above cannot be satisfied by leaving the box blank and
        // re-saving: an empty box and an unreadable box must not read the same way.
        var saved: BondForm? = null
        showBondForm { saved = it }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010")
        fill("Commission", "25")
        pressSave("Buy")

        assertEquals(25.0, saved?.commission ?: -1.0, 1e-9)
    }

    @Test
    fun nothingIsSavedWhileTheTradeIsBeingWritten() {
        var saved: BondForm? = null
        compose.setContent {
            BondEntryForm(
                banks = banks,
                noBankLabel = "No bank",
                saving = true,
                onLookup = {},
                knownBond = null,
                onSave = { saved = it }
            )
        }

        fill("Name", "ОВДП 26/Б")
        fill("Quantity", "20")
        fill("Price/bond", "1010")

        // The button reads "Saving…" and is disabled, so a second tap cannot open a second
        // position the user did not mean to open.
        compose.onNodeWithText("Saving…").assertExists()
        assertNull(saved)
    }

    @Test
    fun noBankIsAccepted() {
        var saved: TransactionForm? = null
        showForm { saved = it }

        fill("Title", "Cash")
        fill("Amount", "50")
        press("Add Transaction")

        // A hand-entered row with no bank is ordinary, not an error: the user may be recording
        // something that never touched an institution.
        assertNotNull(saved)
        assertNull(saved?.bank)
    }
}
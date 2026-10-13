package com.financetracker.ui.transaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financetracker.model.BankCode
import com.financetracker.model.BankRef
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.util.DateFormats
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/**
 * The row as the user reads it: a clean name on top, one secondary line, the date on the
 * right where the note used to be.
 *
 * The raw-title assertions are the point of the whole change — the list used to print the
 * bank's payment purpose verbatim, and this is the test that fails if it ever does again.
 * Robolectric under the JVM, per the project's convention for screen behaviour.
 */
@RunWith(AndroidJUnit4::class)
class TransactionRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun transaction(
        title: String,
        note: String? = null,
        category: String = "mcc_5411"
    ) = Transaction(
        title = title,
        amount = 731.80,
        type = TransactionType.EXPENSE,
        category = category,
        timestamp = 0L,
        currencyCode = "UAH",
        note = note,
        bankCode = BankCode.MONOBANK,
        cardLabel = "4111****2222"
    )

    private fun show(transaction: Transaction, onClick: () -> Unit = {}) {
        compose.setContent {
            TransactionRow(
                transaction = transaction,
                bank = BankRef(BankCode.MONOBANK, "Monobank"),
                dateFormat = DateFormats.date(),
                onClick = onClick
            )
        }
    }

    private fun expectedDate(): String =
        Instant.ofEpochMilli(0L)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateFormats.date())

    @Test
    fun theTitleIsTheCleanedMerchantNotThePaymentPurpose() {
        show(transaction(title = "ОПЛАТА ЗА ТОВАРІВ/ПОСЛУГ NOVUS"))

        compose.onNodeWithText("NOVUS").assertIsDisplayed()
        compose.onNodeWithText("ОПЛАТА ЗА ТОВАРІВ/ПОСЛУГ NOVUS").assertDoesNotExist()
    }

    @Test
    fun theSubtitleCarriesNoteAndAccountWithoutRepeatingTheTitle() {
        show(transaction(title = "ОПЛАТА ЗА ТОВАРІВ/ПОСЛУГ NOVUS", note = "For rent"))

        compose.onNodeWithText("For rent - Monobank • 4111****2222").assertIsDisplayed()
        // The merchant is the title here; printing it again in the subtitle would be the
        // duplication the composition rule exists to prevent.
        compose.onNodeWithText("For rent - Monobank • 4111****2222 - NOVUS").assertDoesNotExist()
    }

    @Test
    fun aJunkTitleFallsBackToTheCategoryAndJunkNeverReappears() {
        show(transaction(title = "ууу", note = "For rent"))

        compose.onNodeWithText("Groceries").assertIsDisplayed()
        compose.onNodeWithText("For rent - Monobank • 4111****2222").assertIsDisplayed()
        compose.onNodeWithText("ууу").assertDoesNotExist()
    }

    @Test
    fun theDateIsShownInItsOwnSlot() {
        show(transaction(title = "NOVUS"))

        compose.onNodeWithText(expectedDate()).assertIsDisplayed()
    }

    @Test
    fun theAmountKeepsItsDirectionSign() {
        show(transaction(title = "NOVUS"))

        compose.onNodeWithText("-731.80", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingTheRowReportsIt() {
        var opened = false
        show(transaction(title = "NOVUS")) { opened = true }

        // The row carries the click, not the text inside it — same matcher as the
        // dashboard's recent-row test, so both prove the same promise.
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("NOVUS")),
            useUnmergedTree = true
        ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

        assertTrue(opened)
    }
}

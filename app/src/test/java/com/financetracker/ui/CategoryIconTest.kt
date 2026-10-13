package com.financetracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FoodBank
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Toys
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.util.CategoryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The icon a row wears is its category; its colour stays the direction.
 *
 * The parity test is the one that matters: it walks every label [CategoryLabel] can
 * produce, so adding a category without giving it an icon fails here instead of silently
 * landing on the free-typed fallback — an icon table that drifts behind the label table
 * is how the list goes back to looking uniform.
 */
class CategoryIconTest {

    private fun tx(
        category: String,
        type: TransactionType = TransactionType.EXPENSE,
        direction: TransferDirection? = null
    ) = Transaction(
        title = "row",
        amount = 100.0,
        type = type,
        category = category,
        timestamp = 0,
        currencyCode = "UAH",
        transferDirection = direction
    )

    @Test
    fun `every canonical label has an icon of its own`() {
        for (label in CategoryLabel.canonicalLabels) {
            assertNotEquals(
                "no icon for canonical label: $label",
                Icons.Default.Label,
                CategoryIcon.forLabel(label)
            )
        }
    }

    @Test
    fun `a transfer keeps the neutral arrow regardless of direction`() {
        val out = tx("investments", TransactionType.TRANSFER, TransferDirection.OUT)
        val `in` = tx("investments", TransactionType.TRANSFER, TransferDirection.IN)

        assertEquals(Icons.Default.SwapVert, CategoryIcon.icon(out))
        assertEquals(CategoryIcon.icon(out), CategoryIcon.icon(`in`))
        // A non-transfer must never wear the arrow: that is the icon meaning "neither
        // green nor red", and a paid grocery row is an expense however it is filed.
        assertNotEquals(Icons.Default.SwapVert, CategoryIcon.icon(tx("mcc_5411")))
    }

    @Test
    fun `the examples the list is read by get their own icons`() {
        assertEquals(Icons.Default.ShoppingBag, CategoryIcon.icon(tx("mcc_5411")))
        assertEquals(Icons.Default.ShowChart, CategoryIcon.icon(tx("investments")))
        assertEquals(Icons.Default.AccountBalanceWallet, CategoryIcon.icon(tx("mcc_6011")))
        assertEquals(Icons.Default.LocalGasStation, CategoryIcon.icon(tx("mcc_5541")))
        // Not through an mcc: no travel code falls inside CategoryLabel's ranges (4722
        // resolves to "Other"), so the label is the only honest way to pin this one.
        assertEquals(Icons.Default.Flight, CategoryIcon.forLabel("Travel & transport"))
        assertEquals(Icons.Default.Restaurant, CategoryIcon.icon(tx("mcc_5812")))
        assertEquals(Icons.Default.LocalHospital, CategoryIcon.icon(tx("mcc_8062")))
        assertEquals(Icons.Default.Receipt, CategoryIcon.icon(tx("imported")))
        assertEquals(Icons.Default.Storefront, CategoryIcon.icon(tx("mcc_5992")))
        assertEquals(Icons.Default.Smartphone, CategoryIcon.icon(tx("mcc_4812")))
        assertEquals(Icons.Default.Spa, CategoryIcon.icon(tx("mcc_5977")))
        assertEquals(Icons.Default.Toys, CategoryIcon.icon(tx("mcc_5945")))
        assertEquals(Icons.Default.Casino, CategoryIcon.icon(tx("mcc_7995")))
        assertEquals(Icons.Default.AccountBalance, CategoryIcon.icon(tx("mcc_9311")))
        assertEquals(Icons.Default.Checkroom, CategoryIcon.icon(tx("mcc_5651")))
        assertEquals(Icons.Default.FoodBank, CategoryIcon.icon(tx("mcc_5412")))
    }

    @Test
    fun `an unrecognised category falls back to the label icon`() {
        // Free-typed wording is the user's own, has no canonical label, and gets the
        // one icon that means "this is a name, not a category".
        assertEquals(Icons.Default.Label, CategoryIcon.icon(tx("мої витрати")))
        assertEquals(Icons.Default.Label, CategoryIcon.icon(tx("birthday gifts")))
    }

    @Test
    fun `no category at all is a receipt, not a label`() {
        assertEquals(Icons.Default.Receipt, CategoryIcon.icon(tx("")))
        assertEquals(Icons.Default.Receipt, CategoryIcon.icon(tx("other")))
        assertEquals(Icons.Default.Receipt, CategoryIcon.icon(tx("imported")))
    }

    @Test
    fun `income and expense of the same category share an icon`() {
        val paid = tx("mcc_5411", TransactionType.EXPENSE)
        val refunded = tx("mcc_5411", TransactionType.INCOME)

        assertEquals(Icons.Default.ShoppingBag, CategoryIcon.icon(paid))
        assertEquals(CategoryIcon.icon(paid), CategoryIcon.icon(refunded))
    }
}

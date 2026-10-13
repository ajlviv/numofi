package com.financetracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FoodBank
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.MiscellaneousServices
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.ui.graphics.vector.ImageVector
import com.financetracker.model.Transaction
import com.financetracker.util.CategoryLabel

/**
 * The icon a row wears: its category, not its direction.
 *
 * Direction never disappears — it stays in the colour of the box and the amount, which
 * [TransactionAppearance] owns — but the shape answers "what kind of spending is this",
 * which is what a person scanning the list is looking for. The old arrows answered only
 * the question the green and red had already answered.
 *
 * Keyed by the canonical English label [CategoryLabel] produces rather than by mcc code,
 * so the two tables stay in step through one path, and [CategoryIconTest] walks
 * [CategoryLabel.canonicalLabels] to fail the moment a label arrives without an icon.
 */
object CategoryIcon {

    /**
     * Free-typed categories are the user's own wording — no table can know them — and
     * get [Label]. Everything canonical is in here; `CategoryIconTest`'s parity test is
     * what keeps that true when either table grows.
     */
    private val BY_LABEL: Map<String, ImageVector> = mapOf(
        "Phone & data" to Icons.Default.Smartphone,
        "Digital services" to Icons.Default.Language,
        "Groceries" to Icons.Default.ShoppingBag,
        "Supermarket" to Icons.Default.FoodBank,
        "Auto parts" to Icons.Default.DirectionsCar,
        "Fuel" to Icons.Default.LocalGasStation,
        "Fuel & auto" to Icons.Default.LocalGasStation,
        "Clothing" to Icons.Default.Checkroom,
        "Home & furniture" to Icons.Default.Weekend,
        "Electronics" to Icons.Default.Devices,
        "Home & electronics" to Icons.Default.Devices,
        "Restaurant" to Icons.Default.Restaurant,
        "Bar & nightlife" to Icons.Default.LocalBar,
        "Fast food" to Icons.Default.Fastfood,
        "Dining & nightlife" to Icons.Default.Restaurant,
        "Pharmacy" to Icons.Default.LocalPharmacy,
        "Cosmetics" to Icons.Default.Face,
        "Health & beauty" to Icons.Default.Spa,
        "Health & general retail" to Icons.Default.LocalPharmacy,
        "Toys & games" to Icons.Default.Toys,
        "General retail" to Icons.Default.Storefront,
        "ATM & cash" to Icons.Default.AccountBalanceWallet,
        "Health services" to Icons.Default.Healing,
        "Other services" to Icons.Default.MiscellaneousServices,
        "Gambling" to Icons.Default.Casino,
        "Professional services" to Icons.Default.BusinessCenter,
        "Membership & leisure" to Icons.Default.CardMembership,
        "Hospital" to Icons.Default.LocalHospital,
        "Accounting & legal" to Icons.Default.Gavel,
        "Government" to Icons.Default.AccountBalance,
        "Public services" to Icons.Default.LocationCity,
        "Travel & transport" to Icons.Default.Flight,
        "Services" to Icons.Default.MiscellaneousServices,
        "Leisure & professional" to Icons.Default.LocalActivity,
        "Imported" to Icons.Default.Receipt,
        "Other" to Icons.Default.Receipt,
        "Investments" to Icons.Default.ShowChart,
        "Uncategorised" to Icons.Default.Receipt,
    )

    /**
     * Transfers are the one case where direction *is* the identity: a bond purchase must
     * not look like a shopping trip however the category was filed. Both directions get
     * the same arrow, the same neutral slate box — the same rule
     * [TransactionAppearanceTest] guards for colour.
     */
    fun icon(transaction: Transaction): ImageVector =
        if (transaction.isTransfer()) Icons.Default.SwapVert
        else forLabel(CategoryLabel.label(transaction.category))

    fun forLabel(label: String): ImageVector = BY_LABEL[label] ?: Icons.Default.Label
}

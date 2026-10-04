package com.financetracker.ui.bond

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.R
import com.financetracker.model.BondPosition
import com.financetracker.ui.MoneyAmount
import com.financetracker.util.DateFormats
import com.financetracker.util.MoneyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Holdings, one card per instrument.
 *
 * Deliberately not editable. A position is folded from trades and is not a thing that can be
 * corrected in place; the only way to change one is to record the trade that changes it, so
 * there is no edit affordance here to imply otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BondListScreen(
    positions: List<BondPosition>,
    onAddTrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        // MainScreen's Scaffold has already reserved the status bar and the navigation bar for
        // this tab, so neither of the bars nested inside it may claim them again: a nested
        // Scaffold and a nested TopAppBar each apply the system insets independently, which
        // stacks an extra status-bar gap above the title and an extra navigation-bar gap under
        // the last card. Stated here rather than left to the default so the ownership is
        // visible — if this screen is ever shown without the outer Scaffold, the inset comes
        // back by removing these two lines.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bond_title)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = onAddTrade) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bond_add_trade_cd))
                    }
                }
            )
        }
    ) { padding ->
        if (positions.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.bond_empty_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bond_empty_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = positions, key = { it.bond.isin }) { position ->
                    PositionCard(position = position)
                }
            }
        }
    }
}

@Composable
private fun PositionCard(position: BondPosition) {
    // Remembered rather than a top-level val: the locale is read once per composition instead
    // of once per class load, so a user who changes it in system settings sees the change.
    val dateFormat = remember { DateFormats.date() }
    val currency = position.bond.nominalCurrency
    val gain = position.unrealised >= 0
    val gainColor = if (gain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = position.bond.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (position.bond.isin.isNotBlank() && !position.bond.isin.equals(position.bond.name, ignoreCase = true)) {
                        Text(
                            text = position.bond.isin,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    // In the bond's own currency. A USD bond is quoted in dollars and a UAH
                    // one in hryvnia, and the symbol on the card is how the two stay apart
                    // without ever being converted.
                    MoneyAmount(
                        amount = position.marketValue,
                        currencyCode = currency,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Figure(stringResource(R.string.bond_figure_held), "${position.quantity}")
                Figure(
                    stringResource(R.string.bond_figure_cost_per),
                    position.averageCost?.let { MoneyFormat.format(it, currency) } ?: "—"
                )
                Figure(stringResource(R.string.bond_figure_unrealised), withSign(position.unrealised, currency), gainColor)
            }

            val terms = bondTerms(
                position = position,
                dateFormat = dateFormat,
                couponFull = stringResource(R.string.bond_coupon_full),
                couponPeriod = stringResource(R.string.bond_coupon_period),
                couponOnly = stringResource(R.string.bond_coupon_only),
                matures = stringResource(R.string.bond_matures)
            )
            if (terms != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = terms,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The one forward-looking figure on this screen, and it says on its face what it is:
            // the principal coming back plus the coupons due before it. Broken out that way
            // because that is the shape of a broker's payment schedule — a run of coupon rows
            // and a final row of principal plus coupon — so a card carrying it can be read
            // against that schedule row for row. What was paid is not part of the sum: a bond
            // repays its principal, so a premium paid over the face value is spent rather than
            // returned. Nothing is shown when the terms to project it are missing.
            position.payout(ZoneId.systemDefault())?.let { payout ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (payout.payments != null) {
                        stringResource(
                            R.string.bond_expected_at_maturity,
                            MoneyFormat.format(payout.nominal, currency),
                            MoneyFormat.format(payout.coupon, currency),
                            payout.payments,
                            MoneyFormat.format(payout.total, currency)
                        )
                    } else {
                        // No coupon period recorded, so there is no schedule to count and the
                        // coupon was pro-rated by day count. The figure carries no count to back it.
                        stringResource(
                            R.string.bond_expected_at_maturity_prorated,
                            MoneyFormat.format(payout.nominal, currency),
                            MoneyFormat.format(payout.coupon, currency),
                            MoneyFormat.format(payout.total, currency)
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (position.quantity == 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bond_fully_sold),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The coupon and maturity line, or null when there is nothing to say.
 *
 * Each part stands on its own: a bond with a coupon but no recorded period still has one, and
 * a period with no coupon has nothing to multiply, so nothing is printed for it. The format
 * templates arrive already resolved through stringResource, because this helper is not a
 * composable; each holds a whole phrase so a translation can reorder the words around the
 * arguments.
 */
private fun bondTerms(
    position: BondPosition,
    dateFormat: DateTimeFormatter,
    couponFull: String,
    couponPeriod: String,
    couponOnly: String,
    matures: String
): String? {
    val parts = mutableListOf<String>()
    val coupon = position.bond.couponPercent
    if (coupon != null) {
        val period = position.bond.couponPeriodMonths
        val annual = position.annualCouponIncome
        val currency = position.bond.nominalCurrency
        parts += when {
            period != null && annual != null -> couponFull.format(
                coupon, period, MoneyFormat.format(annual, currency)
            )

            period != null -> couponPeriod.format(coupon, period)
            else -> couponOnly.format(coupon)
        }
    }
    position.bond.maturityDate?.let { millis ->
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        parts += matures.format(date.format(dateFormat))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun Figure(label: String, value: String, color: Color? = null) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun withSign(value: Double, currency: String): String =
    (if (value > 0) "+" else "") + MoneyFormat.format(value, currency)

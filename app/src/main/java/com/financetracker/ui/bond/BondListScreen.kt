package com.financetracker.ui.bond

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.model.BondPosition
import com.financetracker.ui.MoneyAmount
import com.financetracker.util.MoneyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        topBar = {
            TopAppBar(
                title = { Text("Bonds") },
                actions = {
                    IconButton(onClick = onAddTrade) {
                        Icon(Icons.Default.Add, contentDescription = "Record a trade")
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
                    text = "No bonds yet",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Record a purchase and it will show up here.",
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
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }
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
                    // Not a valuation. This app has no price feed, so the only price it knows
                    // is the one on the last trade the user entered, and a number dressed up
                    // as a current value would be read as one.
                    Text(
                        text = "at last entered price",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Figure("Held", "${position.quantity}")
                Figure(
                    "Cost/bond",
                    position.averageCost?.let { MoneyFormat.format(it, currency) } ?: "—"
                )
                Figure("Unrealised", withSign(position.unrealised, currency), gainColor)
            }

            val terms = bondTerms(position, dateFormat)
            if (terms != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = terms,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (position.quantity == 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Fully sold. Kept here because the trades are.",
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
 * a period with no coupon has nothing to multiply, so nothing is printed for it.
 */
private fun bondTerms(position: BondPosition, dateFormat: DateTimeFormatter): String? {
    val parts = mutableListOf<String>()
    val coupon = position.bond.couponPercent
    if (coupon != null) {
        val period = position.bond.couponPeriodMonths
        val annual = position.annualCouponIncome
        val currency = position.bond.nominalCurrency
        parts += when {
            period != null && annual != null -> "Coupon %.2f%% every %d months — %s a year".format(
                coupon, period, MoneyFormat.format(annual, currency)
            )

            period != null -> "Coupon %.2f%% every %d months".format(coupon, period)
            else -> "Coupon %.2f%%".format(coupon)
        }
    }
    position.bond.maturityDate?.let { millis ->
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        parts += "matures ${date.format(dateFormat)}"
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

package com.financetracker.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.model.BondPosition
import com.financetracker.model.CurrencyTotals
import com.financetracker.model.Transaction
import com.financetracker.model.totalsByCurrency
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.TransactionAppearance
import com.financetracker.util.CategoryLabel
import com.financetracker.util.MoneyFormat


@Composable
fun DashboardScreen(
    transactions: List<Transaction>,
    positions: List<BondPosition> = emptyList(),
    modifier: Modifier = Modifier
) {
    // One set of cards per currency. Summing across currencies and labelling the result
    // with whichever currency was most common produced a figure that looked authoritative
    // and was meaningless. With a single currency, which is the usual case, this renders
    // exactly as it did before.
    val totals = totalsByCurrency(transactions).ifEmpty {
        listOf(CurrencyTotals(currencyCode = null, income = 0.0, expense = 0.0))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        totals.forEach { currency ->
            BalanceCard(balance = currency.balance, currencyCode = currency.currencyCode)

            SummaryRow(
                totalIncome = currency.income,
                totalExpense = currency.expense,
                currencyCode = currency.currencyCode
            )
        }

        // A separate card, not folded into the balance above. The balance is money in the
        // account, and this is money in a security: adding them would be the same mistake as
        // summing across currencies, and would make a sale look like spending money rather than
        // turning part of it into a bond.
        if (positions.isNotEmpty()) {
            // One card per bond denomination, for the same reason the balances split per
            // currency: nothing here may be added to something it is not quoted against.
            positions.groupBy { it.bond.nominalCurrency }.forEach { (currency, inCurrency) ->
                InvestmentCard(positions = inCurrency, currencyCode = currency)
            }
        }

        Text("Recent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        if (transactions.isEmpty()) {
            EmptyState()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                transactions.take(5).forEach { transaction ->
                    TransactionRow(transaction)
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(balance: Double, currencyCode: String?) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                // Not "Total Balance", which it is no longer the whole of. What this shows is
                // cash; a bond holding is worth something and is reported separately, because
                // the two cannot be added without inventing a rate.
                text = "Cash",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // The default mustard is dark, and this card draws on the filled primary colour,
            // so the symbol is brightened here to stay readable on that background.
            MoneyAmount(
                amount = balance,
                currencyCode = currencyCode,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                symbolColor = Color(0xFFF5D98A)
            )
        }
    }
}

@Composable
private fun SummaryRow(totalIncome: Double, totalExpense: Double, currencyCode: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryCard(label = "Income", amount = totalIncome, color = TransactionAppearance.Income, currencyCode = currencyCode)
        SummaryCard(label = "Expenses", amount = totalExpense, color = TransactionAppearance.Expense, currencyCode = currencyCode)
    }
}

/**
 * What the bonds are worth, as one number, and where it is going.
 *
 * Sums across instruments but never across currencies: the caller hands one denomination at
 * a time, and everything here is already in that currency. OВДП may be quoted in UAH, USD or
 * EUR, and a card per currency keeps them apart without inventing a rate.
 */
@Composable
private fun InvestmentCard(positions: List<BondPosition>, currencyCode: String) {
    val held = positions.filter { it.quantity > 0 }
    val value = held.sumOf { it.marketValue }
    val cost = held.sumOf { it.cost }
    val unrealised = value - cost
    val gain = unrealised >= 0
    val annualCoupon = held.sumOf { it.annualCouponIncome ?: 0.0 }
    val heldCount = held.sumOf { it.quantity }

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (positions.size > 1) "Bonds · $currencyCode" else "Bonds",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            MoneyAmount(
                amount = value,
                currencyCode = currencyCode,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "%d held · cost %s".format(heldCount, MoneyFormat.format(cost, currencyCode)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InvestmentFigure(
                    label = "Unrealised",
                    value = withSign(unrealised, currencyCode),
                    color = if (gain) TransactionAppearance.Income else TransactionAppearance.Expense
                )
                if (annualCoupon > 0.0) {
                    InvestmentFigure(
                        label = "Coupon a year",
                        value = MoneyFormat.format(annualCoupon, currencyCode),
                        color = null
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "At the last price you entered. Not a market valuation — this app has no price feed.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun InvestmentFigure(label: String, value: String, color: Color?) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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

@Composable
private fun RowScope.SummaryCard(
    label: String,
    amount: Double,
    color: Color,
    currencyCode: String?
) {
    Card(modifier = Modifier.weight(1f), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            MoneyAmount(
                amount = amount,
                currencyCode = currencyCode,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun TransactionRow(transaction: Transaction) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(TransactionAppearance.tint(transaction)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TransactionAppearance.icon(transaction),
                contentDescription = null,
                tint = TransactionAppearance.accent(transaction),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = CategoryLabel.label(transaction.category),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        MoneyAmount(
            amount = transaction.amount,
            currencyCode = transaction.currencyCode,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = TransactionAppearance.accent(transaction),
            prefix = TransactionAppearance.signPrefix(transaction)
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No transactions yet",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        Text(
            text = "Tap + to add your first",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

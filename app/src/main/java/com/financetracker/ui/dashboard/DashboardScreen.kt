package com.financetracker.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.R
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
    // One card per currency. Summing across currencies and labelling the result with
    // whichever currency was most common produced a figure that looked authoritative and
    // was meaningless.
    val totals = totalsByCurrency(transactions).ifEmpty {
        listOf(CurrencyTotals(currencyCode = null, income = 0.0, expense = 0.0))
    }
    // A currency group is named only when there is more than one: a lone group is
    // unambiguous, but a second one printed below with no name of its own would read as a
    // duplicate of the first. Same rule as the transaction list's summary panel.
    val nameTheCurrency = totals.size > 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        totals.forEach { currency ->
            CurrencySummaryCard(
                currencyCode = currency.currencyCode,
                balance = currency.balance,
                income = currency.income,
                expense = currency.expense,
                showCurrencyLabel = nameTheCurrency
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

        Text(stringResource(R.string.dash_recent), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

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

/**
 * One currency's cash, its income and its expenses, together.
 *
 * These were three separate cards of two different sizes — a filled balance card above a pair
 * of smaller totals — which is why they never lined up: "equal height" was something the
 * layout had to be asked to agree on and could not, because the balance card was taller by its
 * own padding and type scale. One card per currency makes the three the same height by
 * construction rather than by agreement, keeps the two flows on a single row of equal columns,
 * and stops a history in several currencies from repeating a balance-then-totals block down
 * the screen once per currency.
 *
 * The balance stays the largest figure, since it is the one number the screen exists to show.
 * Income and expense keep their own colours so the pair reads without consulting the labels.
 * The balance deliberately takes no colour: it already means something on its own, and a third
 * colour in the row would make the three look like three kinds of the same figure.
 */
@Composable
private fun CurrencySummaryCard(
    currencyCode: String?,
    balance: Double,
    income: Double,
    expense: Double,
    showCurrencyLabel: Boolean
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            if (showCurrencyLabel) {
                Text(
                    text = currencyCode ?: stringResource(R.string.list_no_currency),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                // Not "Total Balance", which it is no longer the whole of. What this shows is
                // cash; a bond holding is worth something and is reported separately, because
                // the two cannot be added without inventing a rate.
                text = stringResource(R.string.dash_cash),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            MoneyAmount(
                amount = balance,
                currencyCode = currencyCode,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
            // Two equal columns, equal in height as well as width: an amount that wraps on one
            // side must not leave the other floating at a different baseline.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SummaryFigure(
                    label = stringResource(R.string.income),
                    amount = income,
                    currencyCode = currencyCode,
                    color = TransactionAppearance.Income
                )
                SummaryFigure(
                    label = stringResource(R.string.expenses),
                    amount = expense,
                    currencyCode = currencyCode,
                    color = TransactionAppearance.Expense
                )
            }
        }
    }
}

/**
 * One side of a currency card's flow row.
 *
 * Takes its own weight rather than accepting a [Modifier], so the two figures cannot be given
 * different widths by a caller and stop lining up with each other or with the balance above.
 */
@Composable
private fun RowScope.SummaryFigure(
    label: String,
    amount: Double,
    currencyCode: String?,
    color: Color
) {
    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(2.dp))
        MoneyAmount(
            amount = amount,
            currencyCode = currencyCode,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
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
                text = if (positions.size > 1) stringResource(R.string.dash_bonds_currency, currencyCode) else stringResource(R.string.dash_bonds),
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
                text = stringResource(R.string.dash_held_cost, heldCount, MoneyFormat.format(cost, currencyCode)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InvestmentFigure(
                    label = stringResource(R.string.dash_unrealised),
                    value = withSign(unrealised, currencyCode),
                    color = if (gain) TransactionAppearance.Income else TransactionAppearance.Expense
                )
                if (annualCoupon > 0.0) {
                    InvestmentFigure(
                        label = stringResource(R.string.dash_coupon_a_year),
                        value = MoneyFormat.format(annualCoupon, currencyCode),
                        color = null
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dash_honest_label),
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
private fun TransactionRow(transaction: Transaction) {
    val categoryRes = CategoryLabel.resource(transaction.category)
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
                text = if (categoryRes != 0) stringResource(categoryRes) else CategoryLabel.label(transaction.category),
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
            text = stringResource(R.string.no_transactions),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        Text(
            text = stringResource(R.string.dash_tap_to_add),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

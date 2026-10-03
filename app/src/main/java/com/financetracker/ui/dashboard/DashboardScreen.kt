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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.R
import com.financetracker.data.settings.DEFAULT_BASE_CURRENCY
import com.financetracker.model.BondPosition
import com.financetracker.model.CountedTransactions
import com.financetracker.model.CurrencyTotals
import com.financetracker.model.ExchangeRates
import com.financetracker.model.ExclusionRules
import com.financetracker.model.NetWorth
import com.financetracker.model.Transaction
import com.financetracker.model.netWorth
import com.financetracker.model.totalsByCurrency
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.TransactionAppearance
import com.financetracker.util.CategoryLabel
import com.financetracker.util.MoneyFormat
import java.time.LocalDate


@Composable
fun DashboardScreen(
    transactions: List<Transaction>,
    counted: CountedTransactions? = null,
    /**
     * The same rules [counted] was derived from, needed because the rules apply to holdings as
     * well as to rows. Null rather than defaulted so a caller that has not wired the settings
     * flow up counts its holdings, which is the honest total.
     */
    rules: ExclusionRules? = null,
    positions: List<BondPosition> = emptyList(),
    baseCurrency: String = DEFAULT_BASE_CURRENCY,
    rates: ExchangeRates = ExchangeRates(emptyMap(), null, 0L),
    onRefreshRates: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Two lists, on purpose. The total is built from `counted`, which has the user's exclusion
    // rules applied, and the recent list below it from `transactions`, which does not. An
    // excluded row is a real row the user imported and may still want to read, find or delete —
    // a rule about what a *total* counts is not a rule about what the app shows.
    //
    // Defaulted to counting everything so a caller that has not wired the rules up yet gets the
    // honest total rather than a silently empty one.
    val rows = counted?.counted ?: transactions
    val totals = totalsByCurrency(rows)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // One card. The dashboard used to split cash into a block per currency on the grounds
        // that a sum across currencies was meaningless — which was true while there was no rate
        // to convert through, and stopped being true the moment there was one. Splitting now
        // would show the same money twice, once unconverted and once converted, and make the
        // user reconcile them.
        NetWorthCard(
            result = netWorth(
                totals = totals,
                positions = positions,
                rates = rates,
                base = baseCurrency,
                excluded = counted?.excluded ?: 0,
                // The holdings cards below stay on every position: a rule is about what a
                // *total* counts, and a holding the user recorded is still worth reading.
                rules = rules ?: ExclusionRules()
            ),
            baseCurrency = baseCurrency,
            rates = rates,
            onRefresh = onRefreshRates
        )

        // A separate card per holding, not folded into the total above. The total is money at
        // nominal, which is a claim about maturity; this is the position and the price the user
        // typed in. One card per bond denomination still, because two denominations have no
        // common price and `marketValue` cannot be added across them.
        if (positions.isNotEmpty()) {
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
 * The one total, and everything it depends on.
 *
 * Three lines under the figure are not decoration, and each is load-bearing:
 *
 * - **what is included** — cash at ledger balance and bonds at nominal. Without it the number
 *   invites being read as a market valuation, which is the one thing it is not, and the bond
 *   card a screen's worth of scrolling below it already says the opposite.
 * - **where the rate came from** — and only when it is more than three days old. A fresh rate
 *   needs no caption; a three-week-old one presented with the same confidence is the case this
 *   line exists for.
 * - **what was left out** — currencies the cache cannot quote. Naming them is the difference
 *   between a total and a total that looks complete when it is not.
 */
@Composable
private fun NetWorthCard(
    result: NetWorth,
    baseCurrency: String,
    rates: ExchangeRates,
    onRefresh: () -> Unit
) {
    val today = remember { LocalDate.now() }
    val stale = remember(rates, today) { rates.isStale(today) }

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = stringResource(R.string.dash_net_worth),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))

            val total = result.total
            if (total == null) {
                Text(
                    // No figure at all rather than a zero. Zero would read as "you own
                    // nothing", which is the one interpretation that must never be reachable by
                    // accident.
                    text = stringResource(R.string.dash_net_worth_no_rate, baseCurrency),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                MoneyAmount(
                    amount = total,
                    currencyCode = baseCurrency,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dash_net_worth_basis),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )

            // Flows, converted on the same terms as the total above and inside the same
            // unquoted gate, so the two lines always add up to the figure they explain. Kept
            // here rather than in blocks per currency: they are properties of the total, not of
            // any one account.
            val income = result.income
            val expense = result.expense
            if (income != null && expense != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    FlowFigure(
                        label = stringResource(R.string.dash_net_worth_income),
                        amount = income,
                        currencyCode = baseCurrency
                    )
                    FlowFigure(
                        label = stringResource(R.string.dash_net_worth_expense),
                        amount = expense,
                        currencyCode = baseCurrency
                    )
                }
            }

            if (stale) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rates.date
                            ?.let { stringResource(R.string.dash_net_worth_stale, it) }
                            ?: stringResource(R.string.settings_rates_never),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh) {
                        Text(
                            text = stringResource(R.string.settings_rates_refresh),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Both omissions are named, and neither is folded into the other. An unquotable
            // currency is fixed by fetching a rate; an excluded row is fixed by editing a rule
            // in Settings. A user who cannot tell which is which has no way to act on either,
            // so they get a line each rather than one combined "what was left out".
            //
            // Printsd above the unquoted lines because it is the one the user caused, and the
            // one they are most likely to be looking for the reason for.
            if (result.excluded > 0) {
                Text(
                    text = stringResource(R.string.dash_net_worth_excluded, result.excluded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // A holding the rules removed, named separately from the rows they removed. A bond
            // purchase is both a cash row and a position, and a rule that catches the row must
            // catch the holding — otherwise the total would quietly gain the full nominal of
            // something already subtracted out of the cash side of the same figure.
            if (result.excludedHoldings > 0) {
                Text(
                    text = stringResource(
                        R.string.dash_net_worth_excluded_holdings,
                        result.excludedHoldings
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            result.unquoted.forEach { currency ->
                Text(
                    text = currency
                        ?.let { stringResource(R.string.dash_net_worth_unrated, it) }
                        ?: stringResource(R.string.dash_net_worth_unrated_no_code),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** One of the two converted flow figures, sharing a line with its counterpart. */
@Composable
private fun RowScope.FlowFigure(label: String, amount: Double, currencyCode: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        MoneyAmount(
            amount = amount,
            currencyCode = currencyCode,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
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
            // Immediately under the figure, as the bond screen puts it. The card also carries
            // `dash_honest_label` at the foot, but a caveat four rows below the number is read
            // after the number, not before it, and this is the largest figure on the card.
            Text(
                text = stringResource(R.string.bond_at_last_price),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
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

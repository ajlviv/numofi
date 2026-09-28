package com.financetracker.ui.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
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
import com.financetracker.model.CurrencyTotals
import com.financetracker.model.Transaction
import com.financetracker.model.totalsByCurrency
import com.financetracker.ui.MoneyAmount
import com.financetracker.util.CategoryLabel

private val IncomeGreen = Color(0xFF2E7D32)
private val ExpenseRed = Color(0xFFC62828)
private val IncomeTint = Color(0xFFE8F5E9)
private val ExpenseTint = Color(0xFFFCE4EC)

@Composable
fun DashboardScreen(
    transactions: List<Transaction>,
    onAddTransaction: () -> Unit,
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
        modifier = modifier.fillMaxSize().padding(16.dp),
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

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onAddTransaction, modifier = Modifier.fillMaxWidth()) {
            Text("Add Transaction")
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
                text = "Total Balance",
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
        SummaryCard(label = "Income", amount = totalIncome, color = IncomeGreen, currencyCode = currencyCode)
        SummaryCard(label = "Expenses", amount = totalExpense, color = ExpenseRed, currencyCode = currencyCode)
    }
}

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
                .background(if (transaction.isIncome()) IncomeTint else ExpenseTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (transaction.isIncome()) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = if (transaction.isIncome()) IncomeGreen else ExpenseRed,
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
            color = if (transaction.isIncome()) IncomeGreen else ExpenseRed
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
            text = "Tap + to add first",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

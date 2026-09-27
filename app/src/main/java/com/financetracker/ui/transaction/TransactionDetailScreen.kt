package com.financetracker.ui.transaction

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.model.Transaction
import com.financetracker.util.CategoryLabel
import com.financetracker.util.MoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transaction: Transaction,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy 'at' HH:mm")
    val date = Instant.ofEpochMilli(transaction.timestamp)
        .atZone(ZoneId.systemDefault()).toLocalDateTime()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Type badge
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = transaction.type.name,
                        fontWeight = FontWeight.Bold,
                        color = if (transaction.isIncome()) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (transaction.isIncome()) Color(0xFFE8F5E9) else Color(0xFFFCE4EC)
                )
            )

            // Amount
            Text(
                text = MoneyFormat.format(transaction.amount, transaction.currencyCode),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (transaction.isIncome()) Color(0xFF2E7D32) else Color(0xFFC62828)
            )

            // Details card
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailRow("Title", transaction.title)
                    DetailRow("Category", CategoryLabel.label(transaction.category))
                    DetailRow("Date", dateFormat.format(date))
                    transaction.note?.let { DetailRow("Note", it) }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
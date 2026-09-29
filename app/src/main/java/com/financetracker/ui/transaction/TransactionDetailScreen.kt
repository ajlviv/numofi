package com.financetracker.ui.transaction

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.model.BankNames
import com.financetracker.model.Transaction
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.TransactionAppearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transaction: Transaction,
    bankNames: Map<String, String>,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Deletion is destructive and final, so it has to survive a mis-tap: the icon only raises
    // the confirmation, and nothing leaves the database until the user says so twice.
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Transaction Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
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
                // The sections below make this taller than the screen on a short device, so it
                // has to scroll. There is no lazy list inside this column, which is what makes
                // scrolling it safe; nesting one would be what crashes.
                .verticalScroll(rememberScrollState())
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
                        color = TransactionAppearance.accent(transaction)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = TransactionAppearance.tint(transaction)
                )
            )

            // Amount
            MoneyAmount(
                amount = transaction.amount,
                currencyCode = transaction.currencyCode,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = TransactionAppearance.accent(transaction),
                prefix = TransactionAppearance.signPrefix(transaction)
            )

            TransactionDetails.sections(
                transaction = transaction,
                // Resolved here rather than at each call site, so the one place that knows
                // the bank list is the one place that turns a stored code into a name.
                bankName = BankNames.display(transaction.bankCode, bankNames)
            ).forEach { section ->
                SectionCard(section)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transaction?") },
            // The title is named because deleting the wrong row is the whole risk this dialog
            // exists to absorb, and it cannot be undone afterwards.
            text = { Text("\"${transaction.title}\" will be permanently deleted.") },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = Color.Red)
                }
            }
        )
    }
}

@Composable
private fun SectionCard(section: DetailSection) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            section.fields.forEach { field -> DetailRow(field) }
        }
    }
}

@Composable
private fun DetailRow(field: DetailField) {
    // Weighted rather than spaced apart, because a stored value can be a long external id or
    // search string: pushed to the far edge it would either wrap under its own label or be
    // clipped, and there is no width at which both fit on one line.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = field.value,
            style = if (field.raw) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f)
        )
    }
}

package com.financetracker.ui.transaction

import java.util.Currency
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    onSave: (Transaction) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(TransactionType.INCOME) }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    fun buildTransactionOrNull(): Transaction? {
        if (title.isBlank() || amount.isBlank()) {
            showError = true
            return null
        }
        val parsedAmount = amount.toDoubleOrNull()
        if (parsedAmount == null || parsedAmount <= 0) {
            showError = true
            return null
        }
        return Transaction(
            title = title.trim(),
            amount = parsedAmount,
            type = selectedType,
            category = category.ifBlank { "Other" },
            timestamp = System.currentTimeMillis(),
            note = note.ifBlank { null },
            // Recorded now so the row is never later labelled with the wrong currency.
            currencyCode = Currency.getInstance(Locale.getDefault()).currencyCode
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Add Transaction") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { buildTransactionOrNull()?.let(onSave) }) {
                        Text("Save", style = MaterialTheme.typography.titleMedium)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                SingleChoiceSegmentedButtonRow {
                    TransactionType.entries.forEach { type ->
                        SegmentedButton(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (type == TransactionType.INCOME) "Income" else "Expense",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    placeholder = { Text("e.g., Salary") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && title.isBlank(),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = showError && (amount.isBlank() || amount.toDoubleOrNull() == null),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    placeholder = { Text("e.g., Food, Transport") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }

            item {
                Button(
                    onClick = { buildTransactionOrNull()?.let(onSave) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = title.isNotBlank() && amount.isNotBlank()
                ) {
                    Text("Add Transaction")
                }
            }
        }
    }
}

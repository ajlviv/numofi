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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.R
import com.financetracker.model.BankNames
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.TransactionAppearance
import com.financetracker.util.CategoryLabel

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
                title = { Text(stringResource(R.string.detail_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            tint = Color.Red
                        )
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
                        text = stringResource(typeNameResource(transaction.type)),
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
                // The sections come back in the English the JVM test pins; the screen is the
                // layer that shows them to a person, so the screen is where they translate.
                SectionCard(section, CategoryLabel.resource(transaction.category))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            // The title is named because deleting the wrong row is the whole risk this dialog
            // exists to absorb, and it cannot be undone afterwards.
            text = { Text(stringResource(R.string.detail_delete_body, transaction.title)) },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.common_delete), color = Color.Red)
                }
            }
        )
    }
}

@Composable
private fun SectionCard(section: DetailSection, categoryResource: Int) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = sectionTitleResource(section.title)
                    .let { if (it != 0) stringResource(it) else section.title },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            section.fields.forEach { field -> DetailRow(field, categoryResource) }
        }
    }
}

@Composable
private fun DetailRow(field: DetailField, categoryResource: Int) {
    val labelResource = fieldLabelResource(field.label)
    // "Not set" is the sentinel TransactionDetails writes for an absent value, and "Manual"
    // is what a hand-entered row resolves to — both are this screen's wording, not stored
    // data, even though they sit in a value column. A category resolves through its key
    // rather than through the English label it arrived with.
    val value = when {
        field.label == "Category" && categoryResource != 0 -> stringResource(categoryResource)
        field.value == TransactionDetails.MISSING -> stringResource(R.string.detail_not_set)
        field.label == "Bank" && field.value == BankNames.MANUAL ->
            stringResource(R.string.common_manual)
        else -> field.value
    }
    // Weighted rather than spaced apart, because a stored value can be a long external id or
    // search string: pushed to the far edge it would either wrap under its own label or be
    // clipped, and there is no width at which both fit on one line.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (labelResource != 0) stringResource(labelResource) else field.label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
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

/** The badge name for a stored type, in the app's language. */
private fun typeNameResource(type: TransactionType): Int = when (type) {
    TransactionType.INCOME -> R.string.income
    TransactionType.EXPENSE -> R.string.common_expense
    TransactionType.TRANSFER -> R.string.common_transfer
}

/**
 * The English field label [TransactionDetails] produces → its resource, 0 when unknown so a
 * field added there but not here still renders instead of crashing on a missing key.
 */
private fun fieldLabelResource(label: String): Int = when (label) {
    "Title" -> R.string.common_title
    "Category" -> R.string.common_category
    "Date" -> R.string.common_date
    "Note" -> R.string.common_note
    "Type" -> R.string.common_type
    "Currency (ISO 4217)" -> R.string.detail_currency_iso
    "Amount (stored)" -> R.string.detail_amount_stored
    "Bank" -> R.string.common_bank
    "Card" -> R.string.detail_card
    "Source" -> R.string.detail_source
    "External ID" -> R.string.detail_external_id
    "Row ID" -> R.string.detail_row_id
    "Timestamp (epoch ms)" -> R.string.detail_timestamp
    else -> 0
}

/** The English section title → its resource, same 0 contract as [fieldLabelResource]. */
private fun sectionTitleResource(title: String): Int = when (title) {
    "Transaction" -> R.string.detail_section_transaction
    "Payment" -> R.string.detail_section_payment
    "Provenance" -> R.string.detail_section_provenance
    "Stored" -> R.string.detail_section_stored
    else -> 0
}

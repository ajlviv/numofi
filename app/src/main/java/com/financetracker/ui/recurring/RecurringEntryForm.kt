package com.financetracker.ui.recurring

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financetracker.R
import com.financetracker.model.Bank
import com.financetracker.model.DEFAULT_RECORDABLE_CURRENCY
import com.financetracker.model.RECORDABLE_CURRENCIES
import com.financetracker.model.RepeatFrequency
import com.financetracker.model.RecurringPayment
import com.financetracker.model.TransactionType
import com.financetracker.ui.component.RequiredLabel
import com.financetracker.ui.component.SingleChoiceDropdown
import com.financetracker.ui.transaction.DateField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The form that creates or edits one schedule.
 *
 * [existing] null means a new one. The two share a screen rather than having separate add and
 * edit screens because every field is the same and only two things differ: the title and
 * whether the write is an insert or an update.
 *
 * The end date is optional and off by default, because most commitments are open-ended and
 * asking for an end date they do not have is how a schedule gets one invented for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurringEntryForm(
    existing: RecurringPayment?,
    banks: List<Bank>,
    onSave: (RecurringForm) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }

    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var amount by remember { mutableStateOf(existing?.amount?.let(::plainAmount).orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: TransactionType.EXPENSE) }
    var category by remember { mutableStateOf(existing?.category.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var currency by remember { mutableStateOf(existing?.currencyCode ?: DEFAULT_RECORDABLE_CURRENCY) }
    var bankCode by remember { mutableStateOf(existing?.bankCode) }
    var frequency by remember { mutableStateOf(existing?.frequency ?: RepeatFrequency.MONTHLY) }
    var interval by remember { mutableStateOf((existing?.intervalCount ?: 1).toString()) }
    var start by remember { mutableStateOf(existing?.startDate?.let { day(it, zone) } ?: today) }
    var end by remember { mutableStateOf(existing?.endDate?.let { day(it, zone) }) }
    var showError by remember { mutableStateOf(false) }
    // Carried, not re-derived: the form offers no control over this flag, so dropping it here
    // would silently un-archive a schedule on every save.
    var archived by remember { mutableStateOf(existing?.archived ?: false) }

    BackHandler(onBack = onCancel)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (existing == null) R.string.recurring_form_new
                            else R.string.recurring_form_edit
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { RequiredLabel(stringResource(R.string.common_title)) },
                modifier = Modifier.fillMaxWidth(),
                isError = showError && title.isBlank(),
                singleLine = true
            )

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { RequiredLabel(stringResource(R.string.common_amount)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                isError = showError && amount.toDoubleOrNull() == null,
                singleLine = true
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEach { option ->
                    SegmentedButton(
                        selected = type == option,
                        onClick = { type = option },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(
                                if (option == TransactionType.INCOME) R.string.income
                                else R.string.common_expense
                            )
                        )
                    }
                }
            }

            SingleChoiceDropdown(
                label = stringResource(R.string.add_currency),
                options = RECORDABLE_CURRENCIES.map { it to it },
                selected = currency,
                onSelect = { currency = it },
                modifier = Modifier.fillMaxWidth()
            )

            SingleChoiceDropdown(
                label = stringResource(R.string.common_bank),
                options = listOf<Pair<String?, String>>(null to stringResource(R.string.add_no_bank)) +
                    banks.map { it.code to it.displayName },
                selected = bankCode,
                onSelect = { bankCode = it },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text(stringResource(R.string.common_category)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.add_note_optional)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            SingleChoiceDropdown(
                label = stringResource(R.string.recurring_frequency),
                options = RepeatFrequency.entries.map { it to frequencyLabel(it) },
                selected = frequency,
                onSelect = { frequency = it },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = interval,
                onValueChange = { entered -> interval = entered.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.recurring_interval)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                isError = showError && (interval.toIntOrNull() ?: 0) < 1,
                singleLine = true
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recurring_start))
                DateField(start) { start = it }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = end != null,
                    onCheckedChange = { checked -> end = if (checked) start else null }
                )
                Text(stringResource(R.string.recurring_end))
                val chosen = end
                if (chosen != null) {
                    DateField(chosen) { end = it }
                } else {
                    Text(
                        text = stringResource(R.string.recurring_end_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = {
                    val parsed = amount.toDoubleOrNull()
                    val every = interval.toIntOrNull() ?: 0
                    if (title.isBlank() || parsed == null || parsed <= 0.0 || every < 1) {
                        showError = true
                        return@Button
                    }
                    onSave(
                        RecurringForm(
                            title = title,
                            amount = parsed,
                            type = type,
                            category = category.ifBlank { "Other" },
                            currencyCode = currency,
                            bankCode = bankCode,
                            note = note.ifBlank { null },
                            frequency = frequency,
                            intervalCount = every,
                            startDate = start,
                            endDate = end,
                            archived = archived
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && amount.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
internal fun frequencyLabel(frequency: RepeatFrequency): String = stringResource(
    when (frequency) {
        RepeatFrequency.WEEKLY -> R.string.recurring_freq_weekly
        RepeatFrequency.MONTHLY -> R.string.recurring_freq_monthly
        RepeatFrequency.YEARLY -> R.string.recurring_freq_yearly
    }
)

/** A stored millis as the local day it falls on. */
private fun day(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** A double without a trailing `.0`, so editing a whole amount does not show `12000.0`. */
private fun plainAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

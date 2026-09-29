package com.financetracker.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.model.BankRef
import com.financetracker.model.Bond
import com.financetracker.model.DEFAULT_RECORDABLE_CURRENCY
import com.financetracker.model.BondMath
import com.financetracker.model.BondTradeSide
import com.financetracker.model.RECORDABLE_CURRENCIES
import com.financetracker.model.TransactionType
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.component.RequiredLabel
import com.financetracker.ui.component.SingleChoiceDropdown
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** What the add screen is currently being used to record. */
enum class AddEntryMode { TRANSACTION, BOND }

/**
 * One screen for two unrelated entries, switched at the top.
 *
 * They share nothing but a header and a save button, and a screen with a mode switch is
 * cheaper than two navigable screens: both are short forms, the user is already in the habit
 * of coming here, and the alternative is a submenu to get to the second one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialMode: AddEntryMode = AddEntryMode.TRANSACTION,
    viewModel: AddTransactionViewModel = hiltViewModel()
) {
    val banks by viewModel.banks.collectAsStateWithLifecycle()
    val knownBond by viewModel.bondSearch.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var mode by remember(initialMode) { mutableStateOf(initialMode) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    // Every way out of this form goes through here, including the system back gesture. A
    // save still in flight when the user leaves is not a save they are watching, and the
    // ViewModel outlives the form, so it has to be told the form is gone or its completion
    // would close the next one on sight.
    val dismiss = {
        viewModel.onFormDismissed()
        onCancel()
    }
    BackHandler(onBack = dismiss)

    // Saved is a one-way event rather than a flag, because the bond path can fail and has to
    // leave the form up. Watching it here means a failure does not navigate away and a success
    // always does. Collected, not read: this ViewModel outlives the form, so a value that is
    // merely still true would be read again by the next opening and close it on sight.
    LaunchedEffect(Unit) {
        viewModel.saved.collect { onSaved() }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Add") },
                navigationIcon = {
                    IconButton(onClick = dismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    AddEntryMode.entries.forEach { entry ->
                        SegmentedButton(
                            selected = mode == entry,
                            onClick = { mode = entry },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (entry == AddEntryMode.TRANSACTION) "Transaction" else "ОВДП",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            val bankRefs = banks.map { BankRef(it.code, it.label()) }

            if (mode == AddEntryMode.TRANSACTION) {
                item {
                    TransactionForm(
                        banks = bankRefs,
                        saving = saving,
                        onSave = { form ->
                            viewModel.saveTransaction(
                                title = form.title,
                                amount = form.amount,
                                type = form.type,
                                category = form.category,
                                date = form.date,
                                note = form.note,
                                bank = form.bank,
                                currencyCode = form.currencyCode
                            )
                        }
                    )
                }
            } else {
                item {
                    BondEntryForm(
                        banks = bankRefs,
                        saving = saving,
                        onLookup = viewModel::findBond,
                        knownBond = knownBond,
                        onSave = { form ->
                            viewModel.saveBondTrade(
                                bond = form.bond,
                                side = form.side,
                                quantity = form.quantity,
                                price = form.price,
                                accruedInterest = form.accrued,
                                commission = form.commission,
                                date = form.date,
                                bank = form.bank,
                                settlementCurrency = form.settlementCurrency,
                                settlementAmount = form.settlementAmount
                            )
                        }
                    )
                }
            }
        }
    }
}

/** What the plain form collected, validated. */
private data class TransactionForm(
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val date: LocalDate,
    val note: String?,
    val bank: BankRef?,
    val currencyCode: String
)

/**
 * The plain transaction form, as one column rather than a list of items.
 *
 * A [LazyColumn] item of its own, so the whole form scrolls as a unit. Given separate items
 * the mode switch above it would scroll away independently, and switching back and forth
 * would put the user at the bottom of a form they had scrolled to the top of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionForm(
    banks: List<BankRef>,
    saving: Boolean,
    onSave: (TransactionForm) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currencyCode by remember { mutableStateOf(DEFAULT_RECORDABLE_CURRENCY) }
    var selectedType by remember { mutableStateOf(TransactionType.INCOME) }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var bank by remember { mutableStateOf<BankRef?>(null) }
    var showError by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Only the two the user can mean by hand. TRANSFER is not offered: every transfer
        // this app records is a bond trade, and letting someone file a bare transfer would
        // let cash leave the balance with nothing to say what it became.
        SingleChoiceSegmentedButtonRow {
            listOf(TransactionType.INCOME, TransactionType.EXPENSE).forEach { type ->
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

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { RequiredLabel("Title") },
            placeholder = { Text("e.g., Salary") },
            modifier = Modifier.fillMaxWidth(),
            isError = showError && title.isBlank(),
            singleLine = true
        )

        // The currency beside the amount rather than on a row of its own, because it
        // qualifies the amount: switching it does not change the number typed, so the two
        // belong together for the reader to check against each other.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { RequiredLabel("Amount") },
                placeholder = { Text("0.00") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = showError && (amount.isBlank() || amount.toDoubleOrNull() == null),
                singleLine = true
            )
            SingleChoiceDropdown(
                label = "Currency",
                options = RECORDABLE_CURRENCIES.map { it to it },
                selected = currencyCode,
                onSelect = { currencyCode = it },
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = category,
            onValueChange = { category = it },
            label = { Text("Category") },
            placeholder = { Text("e.g., Food, Transport") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceDropdown(
                label = "No bank",
                options = listOf(null to "No bank") + banks.map { it as BankRef? to it.label },
                selected = bank,
                onSelect = { bank = it },
                modifier = Modifier.weight(1f)
            )
            DateField(date = date, onChange = { date = it })
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val parsed = amount.toDoubleOrNull()
                if (title.isBlank() || parsed == null || parsed <= 0.0) {
                    showError = true
                    return@Button
                }
                onSave(
                    TransactionForm(
                        title = title.trim(),
                        amount = parsed,
                        type = selectedType,
                        category = category.ifBlank { "Other" },
                        date = date,
                        note = note.ifBlank { null },
                        bank = bank,
                        currencyCode = currencyCode
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            // Disabled while a save is in flight, so a second tap cannot file a second row.
            enabled = title.isNotBlank() && amount.isNotBlank() && !saving
        ) {
            Text(if (saving) "Saving…" else "Add Transaction")
        }
    }
}

/**
 * A date that opens a picker, rather than a free text field.
 *
 * Worth a picker because the whole reason the field exists is that people backdate what they
 * add, and typing "12.03" in whatever order the keyboard suggests is how the wrong date gets
 * recorded and then trusted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(date: LocalDate, onChange: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val pattern = remember { java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }

    Box {
        OutlinedButton(onClick = { showPicker = true }) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null)
            Spacer(modifier = Modifier.height(0.dp))
            Text(
                text = "  " + date.format(pattern),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // Read back as UTC: the picker works in UTC millis, and reading them
                        // in the device zone can land on the previous day for anyone east of
                        // Greenwich.
                        onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

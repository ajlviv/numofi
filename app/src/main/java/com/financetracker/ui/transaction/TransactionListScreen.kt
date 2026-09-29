package com.financetracker.ui.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import com.financetracker.model.BankRef
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.TransactionAppearance
import com.financetracker.ui.component.MultiSelectDropdown
import com.financetracker.util.CategoryLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
private fun TransactionTypeChips(
    selected: TransactionTypeFilter,
    onSelect: (TransactionTypeFilter) -> Unit
) {
    // Always visible and always one of them selected, rather than hidden when no type is
    // chosen. A filter the user has to go looking for is one they will not use to answer the
    // question they opened the list to ask, which on a list this size is usually "what did I
    // spend" or "what moved".
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TransactionTypeFilter.entries.forEach { entry ->
            FilterChip(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                label = { Text(stringResource(entry.labelRes)) }
            )
        }
    }
}

@Composable
fun TransactionListScreen(
    listState: LazyListState = rememberLazyListState(),
    onTransactionClick: (Transaction) -> Unit = {},
    viewModel: TransactionListViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val cardLabels by viewModel.cardLabels.collectAsStateWithLifecycle()
    val banks by viewModel.banks.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy")
    var summaryVisible by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = viewModel::onSearchChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.list_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.list_clear_search))
                        }
                    }
                }
            )
            // Totals for the current filters, so the button is tinted while the panel it opens
            // is up. The state survives rotation, unlike a plain remember, because losing the
            // panel on every rotation is a small thing that still irritates.
            IconButton(onClick = { summaryVisible = !summaryVisible }) {
                Icon(
                    imageVector = Icons.Default.Analytics,
                    contentDescription = if (summaryVisible) stringResource(R.string.list_hide_totals)
                        else stringResource(R.string.list_show_totals),
                    tint = if (summaryVisible) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        AnimatedVisibility(visible = summaryVisible) {
            SummaryPanel(summary)
        }

        FilterRow {
            DateFilterButton(
                filter = filter,
                onRangeChanged = viewModel::onDateRangeChanged,
                onDatesCleared = viewModel::onDatesCleared
            )
            MultiSelectDropdown(
                label = stringResource(R.string.list_banks),
                // From the bank list, not a hardcoded set: an archived bank stays here
                // while transactions still reference it, and bank_archived marks it as
                // archived so it is clear why a retired bank is still being offered.
                options = banks.map { bank ->
                    bank.code to if (bank.archived) {
                        stringResource(R.string.bank_archived, bank.displayName)
                    } else {
                        bank.displayName
                    }
                },
                isSelected = { it in filter.bankCodes },
                onToggle = viewModel::onBankToggled
            )
            // Offered whenever there is anything to offer: the card filter works on its own,
            // and the list is already narrowed to the selected banks when any are picked.
            if (cardLabels.isNotEmpty()) {
                MultiSelectDropdown(
                    label = stringResource(R.string.list_cards),
                    options = cardLabels.map { it to it },
                    isSelected = { it in filter.cardLabels },
                    onToggle = viewModel::onCardToggled
                )
            }
            if (filter.isActive) {
                TextButton(onClick = viewModel::clear) { Text(stringResource(R.string.common_clear)) }
            }
        }

        TransactionTypeChips(selected = filter.type, onSelect = viewModel::onTypeSelected)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (transactions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(hasFilters = search.isNotBlank() || filter.isActive)
                    }
                }
            }
            items(transactions) { transaction ->
                TransactionListItem(
                    transaction = transaction,
                    dateFormat = dateFormat,
                    bank = viewModel.bankOf(transaction.bankCode),
                    onClick = { onTransactionClick(transaction) }
                )
            }
        }
    }
}

/**
 * Totals for the rows the list is currently showing.
 *
 * Grouped per currency, and each group labelled only when there is more than one: a lone group
 * is unambiguous, but a second currency printed underneath the first with no name of its own
 * would read as a duplicate of it. Amounts go through [MoneyAmount] so the currency symbol is
 * styled the same as in the list, and expenses are printed as a positive figure in red the
 * way the list itself prints them, with the sign left to mean something in the net row.
 */
@Composable
private fun SummaryPanel(summary: TransactionSummary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = when {
                    summary.isEmpty -> stringResource(R.string.list_summary_empty)
                    summary.count == 1 -> stringResource(R.string.list_summary_one)
                    else -> stringResource(R.string.list_summary_many, summary.count)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            summary.totals.forEach { totals ->
                if (summary.totals.size > 1) {
                    Text(
                        text = totals.currencyCode ?: stringResource(R.string.list_no_currency),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )
                }
                SummaryAmount(stringResource(R.string.income), totals.income, totals.currencyCode, Color(0xFF2E7D32))
                SummaryAmount(stringResource(R.string.expenses), totals.expense, totals.currencyCode, Color(0xFFC62828))
                // A negative net is the number that matters, so it is the one that changes
                // colour; the other two are coloured by what they always mean.
                SummaryAmount(
                    label = stringResource(R.string.list_net),
                    amount = totals.balance,
                    currencyCode = totals.currencyCode,
                    color = if (totals.balance < 0) Color(0xFFC62828) else Color(0xFF2E7D32)
                )
            }
        }
    }
}

@Composable
private fun SummaryAmount(label: String, amount: Double, currencyCode: String?, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        MoneyAmount(
            amount = amount,
            currencyCode = currencyCode,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun FilterRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterButton(
    filter: TransactionFilter,
    onRangeChanged: (LocalDate?, LocalDate?) -> Unit,
    onDatesCleared: () -> Unit
) {
    // The button is the calendar's own trigger. There used to be a menu in between holding a
    // single "Pick dates" item, which made every date the user picked cost two taps and a menu
    // to read first.
    var pickerVisible by remember { mutableStateOf(false) }
    val from = filter.from
    val to = filter.to

    OutlinedButton(onClick = { pickerVisible = true }) {
        Text(
            text = when {
                from == null && to == null -> stringResource(R.string.common_date)
                from != null && to == null -> stringResource(R.string.list_date_from, from.shortDate())
                to != null && from == null -> stringResource(R.string.list_date_until, to.shortDate())
                from == to -> from!!.shortDate()
                else -> stringResource(R.string.list_date_range, from!!.shortDate(), to!!.shortDate())
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(Icons.Default.CalendarMonth, contentDescription = null)
    }

    if (pickerVisible) {
        DateRangeDialog(
            from = from,
            to = to,
            onDismiss = { pickerVisible = false },
            // Unsetting a filter is decisive and has nothing to review, so Clear applies and
            // closes, rather than waiting for Apply the way a picked range does.
            onClear = {
                onDatesCleared()
                pickerVisible = false
            },
            onApply = { start, end ->
                onRangeChanged(start, end)
                pickerVisible = false
            }
        )
    }
}

/**
 * The date picker, with the presets above it.
 *
 * A preset writes into the picker rather than applying straight away, so the chosen range
 * stays visible and reviewable in the calendar and one button commits it. A picker that
 * applied immediately would leave the user guessing what the tap behind the dialog had
 * actually selected.
 *
 * Clearing is the one exception: there is nothing to review in an empty range, and whoever
 * asked for it has finished with the dialog, so Clear applies at once and closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    from: LocalDate?,
    to: LocalDate?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onApply: (LocalDate?, LocalDate?) -> Unit
) {
    // The picker exposes its selection read-only, so a preset is applied by rebuilding the
    // state around the preset's dates. That puts them in the calendar, where the user can
    // see and adjust them, instead of only in the filter.
    var shown by remember { mutableStateOf(from to to) }
    val state = key(shown) {
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = shown.first?.toPickerMillis(),
            initialSelectedEndDateMillis = shown.second?.toPickerMillis()
        )
    }
    // An AlertDialog was the wrong container for this. Its text slot insets the content by
    // 24dp on each side, and the calendar grid needs the full window width to lay out all
    // seven day columns. Handed less than that, it does not shrink its columns, it clips
    // the last two off the right edge, where they cannot be reached at all.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // A definite height, and not a height cap, because the calendar is now
                    // weighted: a column that only knows an upper bound would wrap its content
                    // and hand the weighted child nothing. Two earlier attempts each broke a
                    // different part of this dialog, so the sizing is spelled out rather than
                    // left to wrap-content.
                    .fillMaxHeight(0.9f)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.list_filter_by_date),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DatePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val (presetFrom, presetTo) = preset.range(LocalDate.now())
                                shown = presetFrom to presetTo
                            },
                            label = { Text(stringResource(preset.labelRes)) }
                        )
                    }
                }
                // The calendar takes whatever height the title, presets and buttons leave, and
                // scrolls inside itself. Left unweighted it would claim all of it and squeeze
                // the Apply button to nothing, which is where the confirm action went missing.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                ) {
                    // Full width of the dialog, which is now the full width of the window, so
                    // the calendar lays out its seven columns at their natural size. This must
                    // stay a finite width: wrapping it in a horizontal scroll, as an earlier
                    // attempt did, handed the grid an unbounded width and it collapsed to a
                    // fraction of the dialog while the header kept its full height.
                    DateRangePicker(
                        state = state,
                        showModeToggle = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Offered only when there is a range to unset: with no dates applied there
                    // is nothing here for it to clear, and an inert button beside Apply is just
                    // a way to lose a tap.
                    if (from != null || to != null) {
                        TextButton(onClick = onClear) { Text(stringResource(R.string.common_clear)) }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        onApply(
                            state.selectedStartDateMillis?.toPickerDate(),
                            state.selectedEndDateMillis?.toPickerDate()
                        )
                    }) { Text(stringResource(R.string.list_apply)) }
                }
            }
        }
    }
}

/**
 * The picker works in UTC midnights, so both directions of the conversion anchor on UTC.
 * Reading them in the device zone would shift a range by a day either side of midnight for
 * anyone east or west of Greenwich.
 */
private fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private fun LocalDate.shortDate(): String = DateTimeFormatter.ofPattern("d MMM").format(this)

@Composable
private fun EmptyState(hasFilters: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.AccountBalanceWallet,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color.Gray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            // An empty result under a filter is a different situation from having no
            // transactions, and offering the add-first-transaction hint while a search
            // is active would be nonsense.
            text = if (hasFilters) stringResource(R.string.list_nothing_matches)
                else stringResource(R.string.no_transactions),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        Text(
            text = if (hasFilters) stringResource(R.string.list_try_clearing)
                else stringResource(R.string.list_tap_to_add),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun TransactionListItem(
    transaction: Transaction,
    dateFormat: DateTimeFormatter,
    bank: BankRef?,
    onClick: () -> Unit
) {
    // A mapped category translates through its cat_* resource; anything else is the
    // user's own typed wording and stays exactly as written.
    val categoryRes = CategoryLabel.resource(transaction.category)
    val categoryText =
        if (categoryRes != 0) stringResource(categoryRes) else CategoryLabel.label(transaction.category)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            // IntrinsicSize.Min lets the trailing column stretch to the row height, and
            // the note below always occupies a line, so rows with a comment are not
            // taller than rows without one.
            modifier = Modifier.padding(16.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon representing transaction type
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(TransactionAppearance.tint(transaction)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TransactionAppearance.icon(transaction),
                    contentDescription = null,
                    tint = TransactionAppearance.accent(transaction),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.title,
                    // Two lines at a smaller size. Imported descriptions run long, and a
                    // single ellipsised line hid exactly the part that identifies the
                    // payment, which is the part the user is scanning the list for.
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$categoryText • ${Instant.ofEpochMilli(transaction.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dateFormat)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                transaction.provenance(bank)?.let { provenance ->
                    Text(
                        text = provenance,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.fillMaxHeight()
            ) {
                MoneyAmount(
                    amount = transaction.amount,
                    currencyCode = transaction.currencyCode,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TransactionAppearance.accent(transaction),
                    prefix = TransactionAppearance.signPrefix(transaction)
                )
                // minLines keeps the slot reserved when there is no note, so every row
                // in the list has the same height.
                Text(
                    text = transaction.note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
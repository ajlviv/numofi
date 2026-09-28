package com.financetracker.ui.transaction

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.model.BankCode
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.util.CategoryLabel
import com.financetracker.util.MoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TransactionType.label: String
    get() = if (this == TransactionType.INCOME) "Income" else "Expense"

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
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy")

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = viewModel::onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            }
        )

        FilterRow {
            DateFilterButton(
                filter = filter,
                onRangeChanged = viewModel::onDateRangeChanged,
                onDatesCleared = viewModel::onDatesCleared
            )
            MultiSelectDropdown(
                label = "Banks",
                options = BankCode.CHOICES.map { it to BankCode.label(it) },
                isSelected = { it in filter.bankCodes },
                onToggle = viewModel::onBankToggled
            )
            MultiSelectDropdown(
                label = "Type",
                options = TransactionType.entries.map { it to it.label },
                isSelected = { it in filter.types },
                onToggle = viewModel::onTypeToggled
            )
            // Offered whenever there is anything to offer: the card filter works on its own,
            // and the list is already narrowed to the selected banks when any are picked.
            if (cardLabels.isNotEmpty()) {
                MultiSelectDropdown(
                    label = "Cards",
                    options = cardLabels.map { it to it },
                    isSelected = { it in filter.cardLabels },
                    onToggle = viewModel::onCardToggled
                )
            }
            if (filter.isActive) {
                TextButton(onClick = viewModel::clear) { Text("Clear") }
            }
        }

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
                    onClick = { onTransactionClick(transaction) }
                )
            }
        }
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

/**
 * A dropdown of checkable options, of which any number may be picked.
 *
 * The menu stays open while picking, because the whole point of a multi-select is that the
 * next choice is usually in the same list, and each row carries a tick rather than a
 * checkbox so that the row is the only thing that toggles. A checkbox inside a menu item
 * would receive the click itself and either swallow it or toggle twice.
 */
@Composable
private fun <T> MultiSelectDropdown(
    label: String,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onToggle: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.filter { isSelected(it.first) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                // One pick reads better than a count of one; the count only earns its place
                // once there is more than one thing to count.
                text = when (selected.size) {
                    0 -> label
                    1 -> selected.first().second
                    else -> "$label · ${selected.size}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onToggle(value) },
                    trailingIcon = if (isSelected(value)) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
            HorizontalDivider()
            TextButton(
                onClick = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Done") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterButton(
    filter: TransactionFilter,
    onRangeChanged: (LocalDate?, LocalDate?) -> Unit,
    onDatesCleared: () -> Unit
) {
    // The button opens a small menu, and the menu is what opens the calendar. These are two
    // separate steps, so they need two separate flags: sharing one meant choosing the menu
    // item that opens the calendar also dismissed the calendar it had just opened.
    var menuExpanded by remember { mutableStateOf(false) }
    var pickerVisible by remember { mutableStateOf(false) }
    val from = filter.from
    val to = filter.to

    Box {
        OutlinedButton(onClick = { menuExpanded = true }) {
            Text(
                text = when {
                    from == null && to == null -> "Date"
                    from != null && to == null -> "From ${from.shortDate()}"
                    to != null && from == null -> "Until ${to.shortDate()}"
                    from == to -> from!!.shortDate()
                    else -> "${from!!.shortDate()} – ${to!!.shortDate()}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.CalendarMonth, contentDescription = null)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Pick dates") },
                onClick = {
                    menuExpanded = false
                    pickerVisible = true
                }
            )
            if (from != null || to != null) {
                DropdownMenuItem(
                    text = { Text("Clear dates") },
                    onClick = {
                        menuExpanded = false
                        onDatesCleared()
                    }
                )
            }
        }
    }
    if (pickerVisible) {
        DateRangeDialog(
            from = from,
            to = to,
            onDismiss = { pickerVisible = false },
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    from: LocalDate?,
    to: LocalDate?,
    onDismiss: () -> Unit,
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
                    text = "Filter by date",
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
                            label = { Text(preset.label) }
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
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        onApply(
                            state.selectedStartDateMillis?.toPickerDate(),
                            state.selectedEndDateMillis?.toPickerDate()
                        )
                    }) { Text("Apply") }
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
            // transactions, and telling the user to "add your first transaction" while a
            // search is active would be nonsense.
            text = if (hasFilters) "Nothing matches" else "No transactions yet",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        Text(
            text = if (hasFilters) "Try clearing the search or filters" else "Tap + to add your first transaction",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun TransactionListItem(
    transaction: Transaction,
    dateFormat: DateTimeFormatter,
    onClick: () -> Unit
) {
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
                    .background(
                        if (transaction.isIncome()) Color(0xFFE8F5E9) else Color(0xFFFCE4EC)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (transaction.isIncome()) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = if (transaction.isIncome()) Color(0xFF2E7D32) else Color(0xFFC62828),
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
                    text = "${CategoryLabel.label(transaction.category)} • ${Instant.ofEpochMilli(transaction.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dateFormat)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                transaction.provenance()?.let { provenance ->
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
                Text(
                    text = "${if (transaction.isIncome()) "+" else "-"}${MoneyFormat.format(transaction.amount, transaction.currencyCode)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (transaction.isIncome()) Color(0xFF2E7D32) else Color(0xFFC62828)
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
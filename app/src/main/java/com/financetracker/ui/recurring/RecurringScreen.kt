package com.financetracker.ui.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import com.financetracker.model.Recurrence
import com.financetracker.model.RecurringPayment
import com.financetracker.model.TransactionType
import com.financetracker.model.UpcomingTotals
import com.financetracker.ui.MoneyAmount
import com.financetracker.util.MoneyFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The scheduled-payments screen: what is committed, and the schedules that say so.
 *
 * The card at the top is a projection, kept apart from the dashboard's net worth on purpose.
 * That total is money that exists; this figure is money that has not moved yet, and folding
 * the two together would put a guess inside a balance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    viewModel: RecurringViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val banks by viewModel.banks.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecurringPayment?>(null) }
    var pendingDelete by remember { mutableStateOf<RecurringPayment?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = message?.let { stringResource(it) }
    LaunchedEffect(message) {
        messageText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    // A successful write closes the form; a failure leaves it open behind the snackbar.
    LaunchedEffect(Unit) {
        viewModel.saved.collect {
            adding = false
            editing = null
        }
    }

    val open = editing
    if (adding || open != null) {
        RecurringEntryForm(
            existing = open,
            banks = banks,
            onSave = { form -> viewModel.save(open?.id, form) },
            onCancel = {
                adding = false
                editing = null
            },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recurring_title)) },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.recurring_add_cd)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (schedules.isEmpty()) {
                item { EmptyState() }
            } else {
                // Only after the empty state is ruled out: with nothing scheduled the card
                // would print "nothing scheduled in this window" directly above "nothing
                // scheduled yet", which is the same fact twice.
                item { UpcomingCard(upcoming, baseCurrency) }

                items(schedules, key = { it.id }) { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        onEdit = { editing = schedule },
                        onToggleArchived = { viewModel.setArchived(schedule, !schedule.archived) },
                        onDelete = { pendingDelete = schedule }
                    )
                }
            }
        }
    }

    pendingDelete?.let { schedule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.recurring_delete_title)) },
            text = { Text(stringResource(R.string.recurring_delete_body, schedule.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(schedule.id)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun UpcomingCard(upcoming: UpcomingTotals, baseCurrency: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.recurring_upcoming_title),
                style = MaterialTheme.typography.titleMedium
            )

            val expense = upcoming.expense
            val income = upcoming.income
            if (expense != null && income != null) {
                Figure(
                    stringResource(R.string.recurring_upcoming_expense),
                    MoneyFormat.format(expense, baseCurrency)
                )
                Figure(
                    stringResource(R.string.recurring_upcoming_income),
                    MoneyFormat.format(income, baseCurrency)
                )
            } else {
                // No rate for the base: the figure is withheld, never shown as zero.
                Text(
                    text = stringResource(R.string.dash_net_worth_no_rate, baseCurrency),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (upcoming.items.isEmpty()) {
                Text(
                    text = stringResource(R.string.recurring_upcoming_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            upcoming.unquoted.forEach { code ->
                Text(
                    text = stringResource(R.string.dash_net_worth_unrated, code ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.recurring_upcoming_honest),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            upcoming.rates.date?.let { date ->
                Text(
                    text = stringResource(R.string.dash_net_worth_stale, date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: RecurringPayment,
    onEdit: () -> Unit,
    onToggleArchived: () -> Unit,
    onDelete: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = schedule.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = frequencySummary(schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MoneyAmount(
                    amount = schedule.amount,
                    currencyCode = schedule.currencyCode,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    prefix = if (schedule.type == TransactionType.INCOME) "+" else "-"
                )
            }

            nextDue(schedule, zone)?.let { next ->
                Text(
                    text = stringResource(R.string.recurring_next_due, next.format(dateFormat)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (schedule.archived) {
                Text(
                    text = stringResource(R.string.recurring_archived),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onToggleArchived) {
                    Text(
                        stringResource(
                            if (schedule.archived) R.string.recurring_unarchive
                            else R.string.recurring_archive
                        )
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.recurring_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.recurring_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** "Monthly", or "Monthly · every 3" when the schedule is not the plain period. */
@Composable
private fun frequencySummary(schedule: RecurringPayment): String {
    val label = frequencyLabel(schedule.frequency)
    return if (schedule.intervalCount > 1) {
        stringResource(R.string.recurring_freq_every, label, schedule.intervalCount)
    } else {
        label
    }
}

/** The next occurrence on or after today, or null once the schedule has ended. */
private fun nextDue(schedule: RecurringPayment, zone: ZoneId): LocalDate? =
    Recurrence.nextOccurrence(
        frequency = schedule.frequency,
        intervalCount = schedule.intervalCount,
        startDateMillis = schedule.startDate,
        endDateMillis = schedule.endDate,
        from = LocalDate.now(zone),
        zone = zone
    )

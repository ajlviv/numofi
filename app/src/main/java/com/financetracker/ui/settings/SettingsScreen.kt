package com.financetracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.financetracker.data.bank.BankProvider
import com.financetracker.data.bank.BankSyncService
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.Bank
import com.financetracker.ui.component.SingleChoiceDropdown
import com.financetracker.ui.statement.StatementImportUiState
import com.financetracker.ui.statement.StatementImportViewModel
import com.financetracker.ui.statement.formatPreviewDate
import com.financetracker.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val selectedBankId by viewModel.selectedBankId.collectAsStateWithLifecycle()
    val tokenInput by viewModel.tokenInput.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isTokenConfigured by viewModel.isTokenConfigured.collectAsStateWithLifecycle()
    val syncDays by viewModel.syncDays.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val banks by viewModel.banks.collectAsStateWithLifecycle()
    val bankNameInput by viewModel.bankNameInput.collectAsStateWithLifecycle()
    val bankMessage by viewModel.bankMessage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            SingleChoiceSegmentedButtonRow {
                ThemeMode.entries.forEach { mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = "Bank connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            BankPicker(
                banks = viewModel.availableBanks,
                selectedBankId = selectedBankId,
                onSelect = viewModel::selectBank
            )

            if (selectedBankId != null) {
                if (isTokenConfigured) {
                    Text(
                        text = lastSyncedAt?.let {
                            "A token is saved for this bank. Last synced ${relativeTime(it)}."
                        } ?: "A token is saved for this bank. Not synced yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SyncRangePicker(
                        days = syncDays,
                        enabled = !isSyncing,
                        onSelect = viewModel::setSyncDays
                    )

                    syncProgress?.let { progress ->
                        Text(
                            text = progressText(progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = viewModel::onTokenChange,
                        label = { Text("Access token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isTokenConfigured) {
                        OutlinedButton(
                            onClick = viewModel::verifyConnection,
                            enabled = !isSyncing
                        ) {
                            Text("Test")
                        }
                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !isSyncing
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Sync now")
                            }
                        }
                        OutlinedButton(onClick = viewModel::clearToken) {
                            Text("Remove token")
                        }
                    } else {
                        Button(
                            onClick = viewModel::saveToken,
                            enabled = tokenInput.isNotBlank()
                        ) {
                            Text("Save token")
                        }
                    }
                }
            } else {
                Text(
                    text = "Select a bank to connect your accounts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = "Import a statement",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Works with any bank: export a statement as XLSX, CSV or PDF and " +
                    "pick it here. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatementImportSection()

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = "Transaction banks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "The names statements can be filed under. Adding one here does not " +
                    "connect to it; it only labels transactions you import yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TransactionBanksSection(
                banks = banks,
                nameInput = bankNameInput,
                message = bankMessage,
                onNameChange = viewModel::onBankNameChange,
                onAdd = viewModel::addBank,
                onRename = viewModel::renameBank,
                onMoveUp = viewModel::moveBankUp,
                onMoveDown = viewModel::moveBankDown,
                onArchiveChange = viewModel::setBankArchived
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = "Language",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            LanguagePicker(
                selected = language,
                onSelect = viewModel::setLanguage
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Finance Tracker v1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = viewModel::signOut,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.sign_out),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun BankPicker(
    banks: List<BankProvider>,
    selectedBankId: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = banks.firstOrNull { it.id == selectedBankId }

    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selected?.displayName ?: "Select a bank")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            banks.forEach { bank ->
                DropdownMenuItem(
                    text = { Text(bank.displayName) },
                    onClick = {
                        onSelect(bank.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * The list of names a transaction can be filed under.
 *
 * Archived banks stay in the list rather than disappearing, because archiving stops a bank
 * being offered for new imports without undoing the transactions already labelled with it,
 * and a bank the user cannot see is a bank they cannot restore.
 */
@Composable
private fun TransactionBanksSection(
    banks: List<Bank>,
    nameInput: String,
    message: String?,
    onNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (String, String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onArchiveChange: (String, Boolean) -> Unit
) {
    var renaming by remember { mutableStateOf<Bank?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = nameInput,
            onValueChange = onNameChange,
            label = { Text("Add a bank") },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        Button(onClick = onAdd, enabled = nameInput.isNotBlank()) { Text("Add") }
    }

    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    banks.forEachIndexed { index, bank ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = bank.displayName, style = MaterialTheme.typography.bodyMedium)
                if (bank.archived) {
                    Text(
                        text = "Archived",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Disabled rather than hidden at the ends, so the row does not change width as the
            // user works down the list.
            IconButton(
                onClick = { onMoveUp(bank.code) },
                enabled = index > 0
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move ${bank.displayName} up")
            }
            IconButton(
                onClick = { onMoveDown(bank.code) },
                enabled = index < banks.lastIndex
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move ${bank.displayName} down")
            }
            IconButton(onClick = { renaming = bank }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${bank.displayName}")
            }
            IconButton(onClick = { onArchiveChange(bank.code, !bank.archived) }) {
                Icon(
                    imageVector = if (bank.archived) {
                        Icons.Default.Unarchive
                    } else {
                        Icons.Default.Archive
                    },
                    contentDescription = if (bank.archived) {
                        "Restore ${bank.displayName}"
                    } else {
                        "Archive ${bank.displayName}"
                    }
                )
            }
        }
    }

    renaming?.let { bank ->
        RenameBankDialog(
            bank = bank,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                onRename(bank.code, name)
                renaming = null
            }
        )
    }
}

@Composable
private fun RenameBankDialog(
    bank: Bank,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // Seeded from the bank rather than held, so reopening the dialog for another bank
    // starts from that bank instead of the last one typed.
    var name by remember(bank.code) { mutableStateOf(bank.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bank") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Ranges offered for statement import. Anything beyond a single provider window needs
 * several paced requests, so the longer ranges are visibly slower.
 */
private val SYNC_RANGES = listOf(7, 30, 90)

@Composable
private fun SyncRangePicker(days: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "How far back the first sync reaches",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SYNC_RANGES.forEach { range ->
                FilterChip(
                    selected = days == range,
                    enabled = enabled,
                    onClick = { onSelect(range) },
                    label = { Text("${range}d") }
                )
            }
        }
    }
}

/** "2 hours ago" style stamp for the last successful sync. */
private fun relativeTime(timestamp: Long): String {
    val elapsed = System.currentTimeMillis() - timestamp
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minute(s) ago"
        minutes < 60 * 24 -> "${minutes / 60} hour(s) ago"
        else -> "${minutes / (60 * 24)} day(s) ago"
    }
}

/**
 * Renders sync progress, spelling out the rate-limit wait so a multi-minute import
 * does not look like a frozen app.
 */
private fun progressText(progress: BankSyncService.SyncProgress): String {
    val base = "Request ${progress.completedRequests}/${progress.totalRequests} " +
        "· ${progress.imported} imported"
    return if (progress.cooldownMillis > 0) {
        val seconds = (progress.cooldownMillis + 999) / 1000
        "$base · rate limit, waiting ${seconds}s"
    } else {
        base
    }
}

/**
 * File picker plus a review step. Importing is only committed after the user has seen
 * how the columns were mapped, which is what catches a wrong date or amount column.
 */
@Composable
private fun StatementImportSection() {
    val viewModel: StatementImportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banks by viewModel.banks.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.onFileSelected(uri) }

    OutlinedButton(
        onClick = {
            picker.launch(
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/pdf"
                )
            )
        },
        enabled = state !is StatementImportUiState.Working
    ) {
        Text("Choose statement file")
    }

    when (val current = state) {
        is StatementImportUiState.Working ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text("Reading file…", style = MaterialTheme.typography.bodyMedium)
            }

        is StatementImportUiState.Preview -> ImportPreviewCard(current, banks, viewModel)

        is StatementImportUiState.Done -> Column {
            Text(
                text = "Imported ${current.imported} transaction(s)" +
                    if (current.alreadySynced > 0) {
                        ", skipped ${current.alreadySynced} already synced from the bank"
                    } else {
                        ""
                    } +
                    if (current.duplicatesSkipped > 0) {
                        ", skipped ${current.duplicatesSkipped} already present."
                    } else {
                        "."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = viewModel::dismiss) { Text("Dismiss") }
        }

        is StatementImportUiState.Error -> Column {
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = viewModel::dismiss) { Text("Dismiss") }
        }

        StatementImportUiState.Idle -> Unit
    }
}

@Composable
private fun ImportPreviewCard(
    state: StatementImportUiState.Preview,
    banks: List<Bank>,
    viewModel: StatementImportViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                // "in this file", because this list is the file being previewed and not the
                // stored transactions. Reading it as the transaction list is what made the
                // preview look like the imported rows had replaced the old ones.
                text = "Found ${state.rows.size} transaction(s) in this file",
                style = MaterialTheme.typography.titleSmall
            )
            state.unitNote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.skippedRows > 0) {
                Text(
                    text = "${state.skippedRows} row(s) had no usable date or amount.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("Bank", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            SingleChoiceDropdown(
                label = "Choose a bank",
                // "None" is a real option rather than an absence, because the file may be
                // from somewhere the app has never heard of and the user still has to be
                // able to say so instead of picking a wrong bank by accident.
                options = listOf(null to "None") + banks.map { it.code to it.displayName },
                selected = state.bankCode,
                onSelect = { viewModel.onBankSelected(it) }
            )
            if (state.bankUnresolved) {
                Text(
                    text = "This file was not recognised. Choose the bank, or the rows " +
                        "cannot be filtered by it later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            state.rows.take(PREVIEW_ROW_COUNT).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = formatPreviewDate(row.timestamp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${if (row.isExpense) "−" else "+"}${row.amount.toPlainString()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (row.description.isNotBlank()) {
                    Text(
                        text = row.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (state.rows.size > PREVIEW_ROW_COUNT) {
                Text(
                    text = "…and ${state.rows.size - PREVIEW_ROW_COUNT} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Disabled rather than failing after the tap, so the requirement is visible
                // before the button is pressed. confirm() still guards it.
                Button(
                    onClick = viewModel::confirm,
                    enabled = !state.bankUnresolved
                ) {
                    Text("Import ${state.rows.size}")
                }
                TextButton(onClick = viewModel::dismiss) { Text("Cancel") }
            }
        }
    }
}

private const val PREVIEW_ROW_COUNT = 4

@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (selected) {
        "uk" -> "Українська"
        "en" -> "English"
        else -> "System default"
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LANGUAGES.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(tag)
                        expanded = false
                    }
                )
            }
        }
    }
}

private val LANGUAGES = listOf(
    SettingsRepository.SYSTEM_DEFAULT to "System default",
    "en" to "English",
    "uk" to "Українська"
)

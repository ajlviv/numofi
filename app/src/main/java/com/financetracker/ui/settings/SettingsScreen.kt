package com.financetracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import com.financetracker.data.backup.BackupStatus
import com.financetracker.data.backup.driveRootUriIfInstalled
import com.financetracker.data.bank.BankProvider
import com.financetracker.data.bank.BankSyncService
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.Bank
import com.financetracker.model.ExchangeRates
import com.financetracker.model.RECORDABLE_CURRENCIES
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateUtils
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
    val backupStatus by viewModel.backupStatus.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val restoreMessage by viewModel.restoreMessage.collectAsStateWithLifecycle()
    val isRestoring by viewModel.isRestoring.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val exclusionRules by viewModel.exclusionRules.collectAsStateWithLifecycle()
    val exclusionInput by viewModel.exclusionInput.collectAsStateWithLifecycle()
    val exclusionMessage by viewModel.exclusionMessage.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.exchangeRates.collectAsStateWithLifecycle()
    val isRefreshingRates by viewModel.isRefreshingRates.collectAsStateWithLifecycle()
    val ratesMessage by viewModel.ratesMessage.collectAsStateWithLifecycle()

    // The language picked below is applied by AppLocale in attachBaseContext, which has
    // already run by the time this screen exists: the only way to apply a new pick is to
    // rebuild the activity. The ViewModel emits after the tag is stored, so the recreation
    // reads the new value instead of racing the write.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.languageChanged.collect { context.findActivity().recreate() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
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
            // The gap between groups is what separates them now that the dividers are gone,
            // and everything inside one group is spaced by the card that holds it.
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = stringResource(R.string.settings_preferences)) {
                RowLabel(stringResource(R.string.settings_theme_mode))
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
                                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                HorizontalDivider()

                RowLabel(stringResource(R.string.settings_language))
                LanguagePicker(
                    selected = language,
                    onSelect = viewModel::setLanguage
                )
            }

            SettingsSection(title = stringResource(R.string.settings_base_currency)) {
                Text(
                    text = stringResource(R.string.settings_base_currency_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                BaseCurrencySection(
                    baseCurrency = baseCurrency,
                    rates = exchangeRates,
                    isRefreshing = isRefreshingRates,
                    message = ratesMessage,
                    onSelect = viewModel::setBaseCurrency,
                    onRefresh = viewModel::refreshRates
                )
            }

            SettingsSection(title = stringResource(R.string.settings_exclusion_rules)) {
                Text(
                    text = stringResource(R.string.settings_exclusion_rules_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExclusionRulesSection(
                    rules = exclusionRules,
                    input = exclusionInput,
                    message = exclusionMessage,
                    onInputChange = viewModel::onExclusionInputChange,
                    onAdd = viewModel::addExclusionRule,
                    onRemove = viewModel::removeExclusionRule
                )
            }

            SettingsSection(title = stringResource(R.string.settings_bank_connection)) {
                BankPicker(
                    banks = viewModel.availableBanks,
                    selectedBankId = selectedBankId,
                    onSelect = viewModel::selectBank
                )

                if (selectedBankId != null) {
                    if (isTokenConfigured) {
                        Text(
                            text = lastSyncedAt?.let {
                                stringResource(R.string.settings_token_saved_synced, relativeTime(it))
                            } ?: stringResource(R.string.settings_token_not_synced),
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
                            label = { Text(stringResource(R.string.settings_access_token)) },
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
                                Text(stringResource(R.string.settings_test))
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
                                    Text(stringResource(R.string.settings_sync_now))
                                }
                            }
                            OutlinedButton(onClick = viewModel::clearToken) {
                                Text(stringResource(R.string.settings_remove_token))
                            }
                        } else {
                            Button(
                                onClick = viewModel::saveToken,
                                enabled = tokenInput.isNotBlank()
                            ) {
                                Text(stringResource(R.string.settings_save_token))
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.settings_select_bank_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                statusMessage?.let { message ->
                    Text(
                        text = message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.import_section_title)) {
                Text(
                    text = stringResource(R.string.import_section_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatementImportSection()
            }

            SettingsSection(title = stringResource(R.string.settings_transaction_banks)) {
                Text(
                    text = stringResource(R.string.settings_transaction_banks_blurb),
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
            }

            SettingsSection(title = stringResource(R.string.settings_account)) {
                OutlinedButton(
                    onClick = viewModel::signOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.sign_out),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                // The version belongs with sign-out rather than floating above it: it is the
                // last thing on the page either way, and inside the card it cannot be read as
                // belonging to the bank list.
                Text(
                    text = stringResource(R.string.settings_version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSection(title = stringResource(R.string.settings_backup)) {
                Text(
                    text = stringResource(R.string.settings_backup_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BackupSection(
                    status = backupStatus,
                    message = backupMessage,
                    onPick = viewModel::onBackupFolderPicked,
                    onBackupNow = viewModel::backupNow,
                    onDisable = viewModel::disableBackup
                )
            }

            SettingsSection(title = stringResource(R.string.settings_restore)) {
                Text(
                    text = stringResource(R.string.settings_restore_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RestoreSection(
                    isRestoring = isRestoring,
                    message = restoreMessage,
                    onPick = viewModel::onRestoreFilePicked
                )
            }
        }
    }
}

/**
 * Resolves a [SettingsMessage] for display: [SettingsMessage.Res] goes through
 * stringResource with its positional arguments (any nested message arguments are
 * resolved first), while [SettingsMessage.Raw] is shown verbatim.
 */
@Composable
private fun SettingsMessage.resolve(): String = when (this) {
    is SettingsMessage.Res -> {
        val resolved = args.map { if (it is SettingsMessage) it.resolve() else it }
        if (resolved.isEmpty()) {
            stringResource(id)
        } else {
            stringResource(id, *resolved.toTypedArray())
        }
    }
    is SettingsMessage.Raw -> text
}

/**
 * The switch, the currency to total in, and where the rate came from.
 *
 * The date is printed NBU's own `dd.MM.yyyy` rather than reformatted into the device's locale.
 * It is what the rate actually is, it is unambiguous in both languages the app ships, and
 * reformatting a date that arrived in a known fixed format would add a translation bug for no
 * gain.
 *
 * The picker and the date only appear once the feature is on. Hiding them while it is off
 * states that a currency means nothing until a total does, which is exactly the relationship
 * between them.
 */
@Composable
private fun BaseCurrencySection(
    baseCurrency: String,
    rates: ExchangeRates,
    isRefreshing: Boolean,
    message: SettingsMessage?,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    SingleChoiceDropdown(
        label = stringResource(R.string.settings_base_currency_pick),
        options = RECORDABLE_CURRENCIES.map { it to it },
        selected = baseCurrency,
        onSelect = onSelect
    )

    Text(
        text = rates.date?.let { stringResource(R.string.settings_rates_as_of, it) }
            ?: stringResource(R.string.settings_rates_never),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(onClick = onRefresh, enabled = !isRefreshing) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(stringResource(R.string.settings_rates_refresh))
        }
    }

    message?.let { message ->
        Text(
            text = message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The user's own rules for what a total may not count.
 *
 * A field and a list, with nothing cleverer, because the rule itself is a plain substring and
 * the screen has to be able to show exactly what is stored — a user who cannot see the rule
 * cannot tell why a total moved, which is the whole reason the omission is reported on the card.
 *
 * The one thing worth spelling out here is that a rule does not hide anything. It keeps rows
 * out of the *totals*; the transactions themselves stay in the list where they can be read and
 * deleted. A user who assumed otherwise would be afraid to add a rule, so the blurb says it.
 */
@Composable
private fun ExclusionRulesSection(
    rules: List<String>,
    input: String,
    message: SettingsMessage?,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.settings_exclusion_rule_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        // Disabled on blank rather than refusing after the tap, so what is required is visible
        // before the button is pressed. An empty rule would match every title and empty every
        // total in the app, so it is never the thing a press stores.
        Button(onClick = onAdd, enabled = input.isNotBlank()) {
            Text(stringResource(R.string.settings_add))
        }
    }

    message?.let {
        Text(
            text = it.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    rules.forEachIndexed { index, pattern ->
        // Hairlines between rows, as in the bank list below: these separate items of the same
        // kind, so they do not compete with the card boundary around the whole group.
        if (index > 0) {
            HorizontalDivider()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pattern,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onRemove(pattern) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.settings_cd_remove_exclusion, pattern)
                )
            }
        }
    }
}

/**
 * A labelled group of related settings.
 *
 * Content sits in a [Card] so the grouping is visible without dividers, which is what the
 * page used to rely on — a hairline between every control, so nothing read as a group of
 * its own and the page scanned as one long list. Dividers are now reserved for separating
 * repeating items *within* a group, where they separate like-from-like instead of competing
 * with the card boundary.
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            // Tonal rather than elevated, so a page of stacked cards stays quiet and the
            // cards do not read as buttons.
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

/** The name of a single control inside a group, e.g. the theme-mode label. */
@Composable
fun RowLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Picking the Drive folder to back up into, and what the last attempt did.
 *
 * The folder picker is launched from here rather than from the ViewModel because it has to
 * come from an activity: there is no way to ask the system for a folder from a plain object,
 * and the chosen URI is handed straight back to [onPick].
 *
 * The switch is the only switch on this screen, and it is here rather than as a button pair
 * because switching a standing preference on and off is exactly what a switch is for — the
 * bank rows above it use buttons because each of those does something different from its
 * unpressed state, rather than being a mode.
 */
@Composable
private fun BackupSection(
    status: BackupStatus,
    message: SettingsMessage?,
    onPick: (Uri) -> Unit,
    onBackupNow: () -> Unit,
    onDisable: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        // Null when the user backs out of the picker, which is not a change to anything.
        tree?.let(onPick)
    }
    val disableLabel = stringResource(R.string.settings_backup_disable)

    // Asked of the package manager once, not on every tap: whether Drive is installed does
    // not change while this screen is up, and the answer is needed to decide where the
    // picker opens.
    val context = LocalContext.current
    val driveRoot = remember(context) { driveRootUriIfInstalled(context) }

    if (status is BackupStatus.Off) {
        // Said outright rather than left to the absence of a switch: the button alone would
        // read as an offer, not as a report that nothing is being copied anywhere.
        Text(
            text = backupStatusText(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            // Opened in Drive rather than wherever the device's own file manager defaults
            // to, which is normally internal storage. This biases where the picker starts
            // and does not restrict where the user may go from there.
            onClick = { picker.launch(driveRoot) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_backup_pick_folder))
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_backup_switch_on),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = backupStatusText(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is BackupStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Switch(
                checked = true,
                onCheckedChange = { onDisable() },
                // The text beside it is not read as a label on its own, so the switch carries
                // what turning it off actually does. Hoisted because semantics is not a
                // composable scope and cannot resolve a string resource itself.
                modifier = Modifier.semantics {
                    contentDescription = disableLabel
                },
                thumbContent = null
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(driveRoot) }) {
                Text(stringResource(R.string.settings_backup_change_folder))
            }
            Button(
                onClick = onBackupNow,
                enabled = status !is BackupStatus.Running
            ) {
                if (status is BackupStatus.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.settings_backup_backup_now))
                }
            }
        }
    }

    message?.let { message ->
        Text(
            text = message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Picks a backup file and merges it in.
 *
 * Not offered inside [BackupSection] even though it is the same file, because the two do
 * opposite things to the folder grant: one writes into it, the other reads any file the user
 * can see, including one the app never wrote. Keeping the read path off the upload controls
 * makes it obvious that a restore is not something the app does to its own folder.
 *
 * No confirmation step, deliberately. A restore only adds rows that were missing and deletes
 * nothing, so the worst outcome of a mis-tap is a file chosen and read; the alternative would
 * be a dialog whose "are you sure" cannot mean anything, because there is nothing to be unsure
 * of until the file is read.
 */
@Composable
private fun RestoreSection(
    isRestoring: Boolean,
    message: SettingsMessage?,
    onPick: (Uri) -> Unit
) {
    // A single document, not a tree: the file may be anywhere the user can reach, including
    // somewhere this app was never granted access to. The MIME list is every type a backup is
    // actually reported as — gzip for what the app writes now, json for what it wrote before
    // it compressed, plain and octet-stream for files that came through something that knows
    // nothing about the type, which would otherwise be invisible in the picker.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { document ->
        // Null when the user backs out, which is not a change to anything.
        document?.let(onPick)
    }

    Button(
        onClick = { picker.launch(RESTORE_MIME_TYPES) },
        enabled = !isRestoring,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isRestoring) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(stringResource(R.string.settings_restore_pick))
        }
    }

    message?.let { message ->
        Text(
            text = message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * What a restore is willing to be offered.
 *
 * The file the app writes is gzip, and the one it wrote before that is json; both are named
 * explicitly because a picker filters on the provider's idea of the type, not the extension, and
 * a type it does not recognise shows no file at all. `application/octet-stream` is the catch-all
 * for a file that arrived through something which knew nothing about it, and being able to
 * select a file that then turns out not to be a backup is better than not being able to select
 * a backup that is.
 */
private val RESTORE_MIME_TYPES = arrayOf(
    "application/gzip",
    "application/x-gzip",
    "application/json",
    "text/plain",
    "application/octet-stream"
)

/**
 * What the last backup did, in words.
 *
 * A failure names the last backup that *worked* rather than the time of the attempt, so the
 * line is a statement about what is in Drive — which is the thing the user cares about when
 * deciding whether to worry.
 */
@Composable
private fun backupStatusText(status: BackupStatus): String = when (status) {
    is BackupStatus.Off -> stringResource(R.string.settings_backup_switch_off)
    is BackupStatus.Running -> stringResource(R.string.settings_backup_in_progress)
    is BackupStatus.Failed -> stringResource(
        R.string.settings_backup_failed,
        status.lastUploadedAt?.let { relativeTime(it) }
            ?: stringResource(R.string.settings_backup_never)
    )
    is BackupStatus.Idle -> status.lastUploadedAt
        ?.let { stringResource(R.string.settings_backup_last_saved, relativeTime(it)) }
        ?: stringResource(R.string.settings_backup_never)
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
            Text(selected?.displayName ?: stringResource(R.string.settings_select_bank))
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
    message: SettingsMessage?,
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
            label = { Text(stringResource(R.string.settings_add_bank)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        Button(onClick = onAdd, enabled = nameInput.isNotBlank()) {
            Text(stringResource(R.string.settings_add))
        }
    }

    message?.let {
        Text(
            text = it.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    banks.forEachIndexed { index, bank ->
        // Hairlines between the rows, now that the group itself is a card: these separate
        // items of the same kind, so they no longer compete with a group boundary.
        if (index > 0) {
            HorizontalDivider()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = bank.displayName, style = MaterialTheme.typography.bodyMedium)
                if (bank.archived) {
                    Text(
                        text = stringResource(R.string.settings_archived_badge),
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
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = stringResource(R.string.settings_cd_move_up, bank.displayName)
                )
            }
            IconButton(
                onClick = { onMoveDown(bank.code) },
                enabled = index < banks.lastIndex
            ) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = stringResource(R.string.settings_cd_move_down, bank.displayName)
                )
            }
            IconButton(onClick = { renaming = bank }) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.settings_cd_rename_bank, bank.displayName)
                )
            }
            IconButton(onClick = { onArchiveChange(bank.code, !bank.archived) }) {
                Icon(
                    imageVector = if (bank.archived) {
                        Icons.Default.Unarchive
                    } else {
                        Icons.Default.Archive
                    },
                    contentDescription = if (bank.archived) {
                        stringResource(R.string.settings_cd_restore_bank, bank.displayName)
                    } else {
                        stringResource(R.string.settings_cd_archive_bank, bank.displayName)
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
        title = { Text(stringResource(R.string.settings_rename_bank)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_bank_name_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
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
            text = stringResource(R.string.settings_sync_range_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SYNC_RANGES.forEach { range ->
                FilterChip(
                    selected = days == range,
                    enabled = enabled,
                    onClick = { onSelect(range) },
                    label = { Text(stringResource(R.string.settings_sync_range_days, range)) }
                )
            }
        }
    }
}

/**
 * Locale-relative "2 hours ago" style stamp for the last successful sync.
 * DateUtils formats the unit words in the active locale, so no unit strings are hardcoded.
 */
private fun relativeTime(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(timestamp).toString()

/**
 * Renders sync progress, spelling out the rate-limit wait so a multi-minute import
 * does not look like a frozen app.
 */
@Composable
private fun progressText(progress: BankSyncService.SyncProgress): String {
    val requests = progress.completedRequests
    val total = progress.totalRequests
    val imported = progress.imported
    return if (progress.cooldownMillis > 0) {
        val seconds = (progress.cooldownMillis + 999) / 1000
        stringResource(
            R.string.settings_sync_progress_waiting,
            requests, total, imported, seconds
        )
    } else {
        stringResource(R.string.settings_sync_progress, requests, total, imported)
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
        Text(stringResource(R.string.import_choose_file))
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
                Text(
                    stringResource(R.string.import_reading_file),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

        is StatementImportUiState.Preview -> ImportPreview(current, banks, viewModel)

        is StatementImportUiState.Done -> Column {
            Text(
                text = when {
                    current.alreadySynced > 0 && current.duplicatesSkipped > 0 ->
                        stringResource(
                            R.string.import_done_both,
                            current.imported,
                            current.alreadySynced,
                            current.duplicatesSkipped
                        )
                    current.alreadySynced > 0 ->
                        stringResource(
                            R.string.import_done_synced,
                            current.imported,
                            current.alreadySynced
                        )
                    current.duplicatesSkipped > 0 ->
                        stringResource(
                            R.string.import_done_duplicates,
                            current.imported,
                            current.duplicatesSkipped
                        )
                    else ->
                        stringResource(R.string.import_done_plain, current.imported)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = viewModel::dismiss) {
                Text(stringResource(R.string.import_dismiss))
            }
        }

        is StatementImportUiState.Error -> Column {
            Text(
                text = current.detail
                    ?.takeIf { it.isNotBlank() }
                    ?.let { detail -> stringResource(current.messageRes) + "\n" + detail }
                    ?: stringResource(current.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = viewModel::dismiss) {
                Text(stringResource(R.string.import_dismiss))
            }
        }

        StatementImportUiState.Idle -> Unit
    }
}

@Composable
private fun ImportPreview(
    state: StatementImportUiState.Preview,
    banks: List<Bank>,
    viewModel: StatementImportViewModel
) {
    // Not a Card: this sits inside the statement import card, and a card inside a card reads
    // as two unrelated things. The divider marks it as the step that follows the picker.
    HorizontalDivider()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            // import_found_in_file says "in this file", because this list is the file being
            // previewed and not the stored transactions. Reading it as the transaction list
            // is what made the preview look like the imported rows had replaced the old ones.
            text = stringResource(R.string.import_found_in_file, state.rows.size),
            style = MaterialTheme.typography.titleSmall
        )
        state.unitNoteRes?.let { note ->
            Text(
                text = stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.skippedRows > 0) {
            Text(
                text = stringResource(R.string.import_skipped_rows, state.skippedRows),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            stringResource(R.string.common_bank),
            style = MaterialTheme.typography.labelLarge
        )
        SingleChoiceDropdown(
            label = stringResource(R.string.import_choose_bank),
            // common_none is a real option rather than an absence, because the file may be
            // from somewhere the app has never heard of and the user still has to be
            // able to say so instead of picking a wrong bank by accident.
            options = listOf(null to stringResource(R.string.common_none)) +
                banks.map { it.code to it.displayName },
            selected = state.bankCode,
            onSelect = { viewModel.onBankSelected(it) }
        )
        if (state.bankUnresolved) {
            Text(
                text = stringResource(R.string.import_bank_unresolved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
                text = stringResource(
                    R.string.import_and_more,
                    state.rows.size - PREVIEW_ROW_COUNT
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Disabled rather than failing after the tap, so the requirement is visible
            // before the button is pressed. confirm() still guards it.
            Button(
                onClick = viewModel::confirm,
                enabled = !state.bankUnresolved
            ) {
                Text(stringResource(R.string.import_import_n, state.rows.size))
            }
            TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.cancel)) }
        }
    }
}

private const val PREVIEW_ROW_COUNT = 4

@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = languageLabel(selected)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LANGUAGES.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(languageLabel(tag)) },
                    onClick = {
                        onSelect(tag)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * The picker label for a language tag. The two endonyms (English, Українська) name
 * themselves and are never translated, so they come from their translatable="false" keys.
 */
@Composable
private fun languageLabel(tag: String): String = when (tag) {
    "uk" -> stringResource(R.string.lang_ukrainian)
    "en" -> stringResource(R.string.lang_english)
    else -> stringResource(R.string.lang_system_default)
}

private val LANGUAGES = listOf(
    SettingsRepository.SYSTEM_DEFAULT,
    "en",
    "uk"
)

/** The activity behind whatever context Compose wrapped, so it can be rebuilt. */
private tailrec fun Context.findActivity(): Activity {
    if (this is Activity) return this
    if (this is ContextWrapper) return baseContext.findActivity()
    error("No activity in the context chain")
}

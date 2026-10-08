package com.financetracker.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.financetracker.BuildConfig
import com.financetracker.data.backup.BackupStatus
import com.financetracker.data.backup.driveRootUriIfInstalled
import com.financetracker.data.bank.BankProvider
import com.financetracker.data.bank.BankSyncService
import com.financetracker.data.settings.DEFAULT_CATEGORIES
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
import com.financetracker.security.LocalExternalActivityLaunch
import com.financetracker.security.LockCapability
import com.financetracker.util.CategoryLabel

/**
 * The three groups the page is split into, and the label each is titled by.
 *
 * Grouped by what a setting is *for*, not by how much of the page it takes: General is how the
 * app looks and what it is allowed to count, Data is how rows get in and how they are kept, and
 * Account is the signed-in identity. Ten sections in one scroll column buried the heaviest of
 * them — statement import sat sixth, under four cards — and a tab is what lets a group be found
 * without reading past the ones above it.
 *
 * An enum rather than an index so `rememberSaveable` has a name to write down and the `when`
 * that picks the content cannot pair a label with the wrong sections.
 */
private enum class SettingsTab(@StringRes val labelRes: Int) {
    GENERAL(R.string.settings_tab_general),
    DATA(R.string.settings_tab_data),
    ACCOUNT(R.string.settings_tab_account),
    CATEGORIES(R.string.settings_tab_categories)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // The language picked below is applied by AppLocale in attachBaseContext, which has
    // already run by the time this screen exists: the only way to apply a new pick is to
    // rebuild the activity. The ViewModel emits after the tag is stored, so the recreation
    // reads the new value instead of racing the write.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.languageChanged.collect { context.findActivity().recreate() }
    }

    // Asked on composition rather than held: a fingerprint the user adds or removes in the
    // device's own settings is picked up by returning to this screen, and a value read at
    // launch would still be offering a switch for a credential that is no longer there. Kept
    // here rather than moved into the General tab that shows it, so the answer is already in
    // hand by the time that tab is opened rather than arriving a frame after the switch.
    LaunchedEffect(Unit) { viewModel.refreshLockCapability(context) }

    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.GENERAL) }

    // Held out here rather than created inside each tab, because only the tab on screen is
    // composed: a state created in there is dropped the moment the user switches away, and
    // coming back would land at the top of a list they had already scrolled. Each one is
    // saveable in its own right, so a rotation also returns to the same place in the same tab.
    val generalScroll = rememberScrollState()
    val dataScroll = rememberScrollState()
    val accountScroll = rememberScrollState()
    val categoriesScroll = rememberScrollState()

    Scaffold(
        // The tabs are drawn inside MainScreen's Scaffold, which has already reserved the
        // system bars for them. Both the Scaffold and the TopAppBar below would otherwise add
        // their own inset on top — see the same note on the bonds screen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Bar and strip stacked by hand rather than using the bar's own bottom slot, which
            // this version of Material3 does not have. Wrapped in a Column inside `topBar` for
            // the same reason the slot exists: `Scaffold` measures what `topBar` returns as one
            // bar, so the padding handed down covers the strip too, and the strip stays put
            // while the tab's sections scroll underneath.
            Column {
                TopAppBar(
                    // The build, not the word "Settings": the bottom bar already names this tab,
                    // so the header carries the identity the Account tab used to show.
                    title = {
                        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
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
                SecondaryTabRow(
                    selectedTabIndex = SettingsTab.entries.indexOf(selectedTab)
                ) {
                    SettingsTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            text = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // Only the selected tab is composed, which is the whole reason the state below is
        // collected per tab instead of in one block here: a hidden tab holds no subscriptions,
        // and the import section's ViewModel is left untouched until Data is actually opened.
        val tabModifier = modifier.padding(padding)
        when (selectedTab) {
            SettingsTab.GENERAL -> GeneralTab(
                modifier = tabModifier,
                scrollState = generalScroll,
                viewModel = viewModel
            )
            SettingsTab.DATA -> DataTab(
                modifier = tabModifier,
                scrollState = dataScroll,
                viewModel = viewModel
            )
            SettingsTab.ACCOUNT -> AccountTab(
                modifier = tabModifier,
                scrollState = accountScroll,
                viewModel = viewModel
            )
            SettingsTab.CATEGORIES -> CategoriesTab(
                modifier = tabModifier,
                scrollState = categoriesScroll,
                viewModel = viewModel
            )
        }
    }
}

/**
 * The scrolling column behind each tab.
 *
 * One wrapper so all three get the same 16dp inset and the same gap between groups. The gap is
 * what separates them now that the dividers are gone, and everything inside one group is spaced
 * by the card that holds it.
 */
@Composable
private fun SettingsTabContent(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        content = content
    )
}

/**
 * How the app presents itself, and what it counts.
 *
 * Sections are in the order they always were, and the four of them stay separate cards rather
 * than being merged: each has its own blurb, and a single card holding a rate date and a list of
 * substrings would read as one setting with two halves rather than two settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralTab(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val exclusionRules by viewModel.exclusionRules.collectAsStateWithLifecycle()
    val exclusionInput by viewModel.exclusionInput.collectAsStateWithLifecycle()
    val exclusionMessage by viewModel.exclusionMessage.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.exchangeRates.collectAsStateWithLifecycle()
    val isRefreshingRates by viewModel.isRefreshingRates.collectAsStateWithLifecycle()
    val ratesMessage by viewModel.ratesMessage.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val lockCapability by viewModel.lockCapability.collectAsStateWithLifecycle()

    SettingsTabContent(scrollState = scrollState, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_preferences)) {
            RowLabel(stringResource(R.string.settings_theme_mode))
            // `space` is the dimension the segments overlap by, so a negative value is the gap
            // between them. The default (BorderWidth) is what fuses them into a single control,
            // which is the look being replaced here.
            SingleChoiceSegmentedButtonRow(space = (-8).dp) {
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

        SettingsSection(title = stringResource(R.string.settings_app_lock)) {
            AppLockSection(
                enabled = appLockEnabled,
                capability = lockCapability,
                onChange = viewModel::setAppLockEnabled
            )
        }
    }
}

/**
 * Where the categories the app assigns and the ones the user types live together.
 *
 * Lifted out of General so that tab reads as "how the app looks and counts" (preferences,
 * base currency, exclusions, app lock) and this one is purely about the labels transactions
 * carry — the same separation General already draws by keeping its own sections apart. It is
 * its own tab rather than a General subsection because a typed category is user data, not a
 * preference, and deserves the same weight as the banks and backups Data holds.
 */
@Composable
private fun CategoriesTab(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoryInput by viewModel.categoryInput.collectAsStateWithLifecycle()
    val categoryMessage by viewModel.categoryMessage.collectAsStateWithLifecycle()

    SettingsTabContent(scrollState = scrollState, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_categories)) {
            Text(
                text = stringResource(R.string.settings_categories_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CategoriesSection(
                categories = categories,
                input = categoryInput,
                message = categoryMessage,
                onInputChange = viewModel::onCategoryInputChange,
                onAdd = viewModel::addCategory,
                onRemove = viewModel::removeCategory,
                onReset = viewModel::resetCategories
            )
        }
    }
}

/**
 * How transactions get in, and how they are kept.
 *
 * The five sections here are the ones that touch rows rather than the way rows are shown, which
 * is why they sit together: a bank connection and a statement import are two doors to the same
 * data, a transaction bank is the label that data ends up carrying, and backup and restore are
 * the two halves of keeping a copy of it.
 */
@Composable
private fun DataTab(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel
) {
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

    SettingsTabContent(scrollState = scrollState, modifier = modifier) {
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

/**
 * Who is signed in, and nothing else.
 *
 * The one section with no state behind it, which is why the tab holds a single card: sign-out is
 * a consequence of an identity rather than a preference, and putting it beside appearance and
 * totals would invite the reading that it is one of those.
 */
@Composable
private fun AccountTab(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel
) {
    SettingsTabContent(scrollState = scrollState, modifier = modifier) {
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
 * The user's own category list — the set the dropdown offers in every form.
 *
 * Lifted onto its own tab to separate user data (a category a row carries) from preference
 * (how the app counts). Each label resolves through [CategoryLabel.resource] so a stored
 * English key — "Fast food", "Travel & transport" — renders in the selected language instead
 * of echoing the key, which is what let multi-word defaults leak through in English.
 */
@Composable
private fun CategoriesSection(
    categories: List<String>,
    input: String,
    message: SettingsMessage?,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.settings_category_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        // Disabled on blank rather than refusing after the tap, so what is required is visible
        // before the button is pressed, matching ExclusionRulesSection.
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

    categories.forEachIndexed { index, name ->
        // Hairlines between rows, as in ExclusionRulesSection: these separate items of the
        // same kind, so they do not compete with the card boundary around the whole group.
        if (index > 0) {
            HorizontalDivider()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The same pattern CategoryDropdown uses: a row's category is a stored English
            // key, so a cat_* resource is rendered via stringResource and a free-typed name
            // falls back to echoing it. The `cat_` lookup is the translation; the raw key is
            // never shown for anything the table names.
            val res = CategoryLabel.resource(name)
            Text(
                text = if (res != 0) stringResource(res) else name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onRemove(name) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.settings_cd_remove_category, name)
                )
            }
        }
    }

    // A reset is only offered once there is something to reset to: on the defaults already,
    // there is nothing the button could do that the list is not already showing.
    if (categories != DEFAULT_CATEGORIES && categories.isNotEmpty()) {
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.settings_categories_reset))
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

/**
 * The switch that requires the device's own credential, and what this device would ask for.
 *
 * The capability line is the reason the switch can be greyed out, so it is stated before the
 * switch rather than as the switch's disabled reason: a control that cannot be pressed with no
 * explanation is the thing users report as a bug.
 *
 * What the section deliberately does not do is name a modality. `BiometricManager` reports what
 * class of authenticator is enrolled, not which one, so "fingerprint or face" is the most either
 * answer can honestly support — naming a fingerprint on a device whose only biometric is a face
 * would be a promise the prompt does not keep.
 *
 * Nothing is shown while [capability] is null: the first composition has asked the device and has
 * not been told yet, and a line that changes a moment after appearing is worse than no line.
 */
@Composable
private fun AppLockSection(
    enabled: Boolean,
    capability: LockCapability?,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_app_lock_switch),
                style = MaterialTheme.typography.bodyMedium
            )
            capability?.let {
                Text(
                    text = stringResource(it.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val switchDescription = stringResource(R.string.settings_app_lock_cd_switch)
        Switch(
            checked = enabled,
            // Disabled on nothing enrolled rather than enabled and refused on tap: the flag
            // describes what will be asked for at the next launch, so turning it on without
            // anything to ask for would store a lock no credential on this device can open.
            enabled = capability?.canLock == true,
            onCheckedChange = onChange,
            // The label beside the switch is a label, not a description of the consequence, so
            // the switch carries what turning it on actually does. Hoisted because semantics is
            // not a composable scope and cannot resolve a string resource itself.
            modifier = Modifier.semantics {
                contentDescription = switchDescription
            },
            thumbContent = null
        )
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
    val externalLaunch = LocalExternalActivityLaunch.current

    // Announced rather than inferred: the picker is its own activity, so it stops this one, and
    // an app lock that re-locked on every stop would unmount the settings section while the
    // picker was in front and drop the result on the floor.
    val openPicker: (Uri?) -> Unit = { tree -> externalLaunch?.expect(); picker.launch(tree) }

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
onClick = { openPicker(driveRoot) },
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
            OutlinedButton(onClick = { openPicker(driveRoot) }) {
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

    // Same reason as the backup picker above: this is its own activity, and an app lock that
    // re-locked on the stop would leave the restore with nowhere to deliver what was chosen.
    val externalLaunch = LocalExternalActivityLaunch.current

    Button(
        onClick = {
            externalLaunch?.expect()
            picker.launch(RESTORE_MIME_TYPES)
        },
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

    // Third picker, and the same announcement: a file chooser is its own activity, so without
    // this the lock would unmount the settings screen while the user was picking a statement and
    // the import would start against a section that no longer existed.
    val externalLaunch = LocalExternalActivityLaunch.current

    OutlinedButton(
        onClick = {
            externalLaunch?.expect()
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

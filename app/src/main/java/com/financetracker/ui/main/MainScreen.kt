package com.financetracker.ui.main

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import com.financetracker.ui.bond.BondListScreen
import com.financetracker.ui.dashboard.DashboardScreen
import com.financetracker.ui.recurring.RecurringScreen
import com.financetracker.ui.settings.SettingsScreen
import com.financetracker.ui.transaction.AddEntryMode
import com.financetracker.ui.transaction.AddTransactionScreen
import com.financetracker.ui.transaction.TransactionDetailScreen
import com.financetracker.ui.transaction.TransactionListScreen

enum class MainBottomNavDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", R.string.dashboard, Icons.Default.Dashboard),
    TRANSACTIONS("transactions", R.string.transactions, Icons.AutoMirrored.Filled.List),
    BONDS("bonds", R.string.main_bonds, Icons.Default.AccountBalance),
    SCHEDULED("scheduled", R.string.main_scheduled, Icons.Default.EventRepeat),
    SETTINGS("settings", R.string.settings, Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    // All three saveable, deliberately: rotation is a configuration change and not a reason to
    // throw the user back to the dashboard with the tab they were on reset. The list state two
    // lines below already was, which made the inconsistency easier to miss than it looks.
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var addEntryMode by rememberSaveable { mutableStateOf<AddEntryMode?>(null) }
    // The id, not the row. A row held as a value is a snapshot of the moment it was tapped,
    // so anything written to it afterwards — a type the user corrects, a title edited
    // elsewhere — leaves this screen showing what the row used to say. Resolving the id
    // against the live flow means the detail always shows the current row, and it disappears
    // by itself if the row is deleted from anywhere.
    var selectedTransactionId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Back has to be answered by hand, because nothing here is a destination: the Scaffold is
    // swapped rather than pushed, so the dispatcher would otherwise hand the gesture to the
    // system and finish the activity from the middle of a transaction's details. The order is
    // the order the user's own stack is in — close what is on top of the dashboard, then go to
    // the dashboard, and only then leave. The add form and the detail screen each register
    // their own handler later in composition, so the innermost one wins and the add form still
    // tells its own ViewModel that it was dismissed.
    BackHandler(
        enabled = addEntryMode != null || selectedTransactionId != null || selectedTab != 0
    ) {
        when {
            addEntryMode != null -> addEntryMode = null
            selectedTransactionId != null -> selectedTransactionId = null
            else -> selectedTab = 0
        }
    }

    // Held here rather than inside TransactionListScreen: opening an item replaces the
    // whole Scaffold, so a list state remembered in there is discarded and the list
    // jumps back to the top on return. rememberSaveable also survives process death.
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val counted by viewModel.counted.collectAsStateWithLifecycle()
    val exclusionRules by viewModel.exclusionRules.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val bankNames by viewModel.bankNames.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.exchangeRates.collectAsStateWithLifecycle()
    val upcomingPlans by viewModel.upcomingPlans.collectAsStateWithLifecycle()

    // On entering the dashboard rather than on a timer: the question is whether this screen has
    // been seen since the last daily rate, not how long it has been open.
    LaunchedEffect(Unit) { viewModel.refreshRatesIfStale() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Resolved here, in composable scope: LaunchedEffect's body is a coroutine where
    // stringResource is not available, so the snackbar copy is fetched before it starts.
    val snackbarText = message?.let { stringResource(it) }
    LaunchedEffect(message) {
        snackbarText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    addEntryMode?.let { initialMode ->
        AddTransactionScreen(
            initialMode = initialMode,
            // The screen saves for itself: it owns its own ViewModel and writes the row
            // against the signed-in uid, so there is no transaction to hand back here.
            onSaved = { addEntryMode = null },
            onCancel = { addEntryMode = null },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    val selectedTransaction = selectedTransactionId?.let { id ->
        transactions.firstOrNull { it.id == id }
    }

    // A row that no longer exists — deleted from the detail, or by wiping the account in
    // settings — must not leave the screen open on nothing, and must not leave an id behind
    // that could match a row arriving later. Cleared here rather than in the branch below,
    // because assigning to state during composition is what makes a recomposition loop.
    LaunchedEffect(selectedTransactionId, selectedTransaction) {
        if (selectedTransactionId != null && selectedTransaction == null) {
            selectedTransactionId = null
        }
    }

    selectedTransaction?.let { transaction ->
        TransactionDetailScreen(
            transaction = transaction,
            bankNames = bankNames,
            onDelete = {
                viewModel.deleteTransaction(transaction.id)
                selectedTransactionId = null
            },
            onTypeChange = { kind ->
                viewModel.setTransactionType(transaction.id, kind)
            },
            onBack = { selectedTransactionId = null },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    val items = MainBottomNavDestination.entries

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Only where something can be added. The transactions list is a browsing screen,
            // the settings screen is a list of controls, and the bonds screen has its own
            // button in its app bar for the one thing that can be added there.
            if (items[selectedTab] == MainBottomNavDestination.DASHBOARD || items[selectedTab] == MainBottomNavDestination.TRANSACTIONS) {
                FloatingActionButton(onClick = { addEntryMode = AddEntryMode.TRANSACTION }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.main_add_transaction))
                }
            }
        },
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(destination.icon, contentDescription = stringResource(destination.labelRes)) },
                        // Five destinations leave each label roughly 72dp on a 360dp screen,
                        // which is not enough for "Transactions". Wrapping to a second line
                        // pushed the icon row up and made every tab a different height, so the
                        // label is pinned to one line and gives up its tail instead.
                        label = {
                            Text(
                                text = stringResource(destination.labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (items[selectedTab]) {
                MainBottomNavDestination.DASHBOARD ->
                    DashboardScreen(
                        transactions = transactions,
                        counted = counted,
                        rules = exclusionRules,
                        positions = positions,
                        baseCurrency = baseCurrency,
                        rates = exchangeRates,
                        upcoming = upcomingPlans,
                        onRefreshRates = viewModel::refreshRates,
                        // Same destination as the list below, so a row on the dashboard opens the
                        // same detail screen the Transactions tab would open it in.
                        onTransactionClick = { selectedTransactionId = it.id }
                    )

                MainBottomNavDestination.TRANSACTIONS ->
                    TransactionListScreen(
                        listState = listState,
                        onTransactionClick = { selectedTransactionId = it.id }
                    )

                MainBottomNavDestination.BONDS ->
                    BondListScreen(positions = positions, onAddTrade = { addEntryMode = AddEntryMode.BOND })

                MainBottomNavDestination.SCHEDULED ->
                    RecurringScreen()

                MainBottomNavDestination.SETTINGS ->
                    SettingsScreen(onBack = {}, showBackButton = false)
            }
        }
    }
}

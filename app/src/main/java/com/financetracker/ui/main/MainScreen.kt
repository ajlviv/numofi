package com.financetracker.ui.main

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import com.financetracker.model.Transaction
import com.financetracker.ui.bond.BondListScreen
import com.financetracker.ui.dashboard.DashboardScreen
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
    SETTINGS("settings", R.string.settings, Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableStateOf(0) }
    var addEntryMode by remember { mutableStateOf<AddEntryMode?>(null) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    // Held here rather than inside TransactionListScreen: opening an item replaces the
    // whole Scaffold, so a list state remembered in there is discarded and the list
    // jumps back to the top on return. rememberSaveable also survives process death.
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val bankNames by viewModel.bankNames.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.exchangeRates.collectAsStateWithLifecycle()

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

    selectedTransaction?.let { transaction ->
        TransactionDetailScreen(
            transaction = transaction,
            bankNames = bankNames,
            onDelete = {
                viewModel.deleteTransaction(transaction.id)
                selectedTransaction = null
            },
            onBack = { selectedTransaction = null },
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
            if (items[selectedTab] == MainBottomNavDestination.DASHBOARD) {
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
                        label = { Text(stringResource(destination.labelRes)) }
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
                        positions = positions,
                        baseCurrency = baseCurrency,
                        rates = exchangeRates,
                        onRefreshRates = viewModel::refreshRates
                    )

                MainBottomNavDestination.TRANSACTIONS ->
                    TransactionListScreen(
                        listState = listState,
                        onTransactionClick = { selectedTransaction = it }
                    )

                MainBottomNavDestination.BONDS ->
                    BondListScreen(positions = positions, onAddTrade = { addEntryMode = AddEntryMode.BOND })

                MainBottomNavDestination.SETTINGS ->
                    SettingsScreen(onBack = {}, showBackButton = false)
            }
        }
    }
}

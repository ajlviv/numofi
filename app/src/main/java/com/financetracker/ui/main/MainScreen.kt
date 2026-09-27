package com.financetracker.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.model.Transaction
import com.financetracker.ui.dashboard.DashboardScreen
import com.financetracker.ui.settings.SettingsScreen
import com.financetracker.ui.transaction.AddTransactionScreen
import com.financetracker.ui.transaction.TransactionDetailScreen
import com.financetracker.ui.transaction.TransactionListScreen

enum class MainBottomNavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Default.Dashboard),
    TRANSACTIONS("transactions", "Transactions", Icons.Default.List),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showAddTransaction by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    // Held here rather than inside TransactionListScreen: opening an item replaces the
    // whole Scaffold, so a list state remembered in there is discarded and the list
    // jumps back to the top on return. rememberSaveable also survives process death.
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (showAddTransaction) {
        AddTransactionScreen(
            onSave = { transaction ->
                viewModel.addTransaction(transaction)
                showAddTransaction = false
            },
            onCancel = { showAddTransaction = false },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    selectedTransaction?.let { transaction ->
        TransactionDetailScreen(
            transaction = transaction,
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
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
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
                        onAddTransaction = { showAddTransaction = true }
                    )

                MainBottomNavDestination.TRANSACTIONS ->
                    TransactionListScreen(
                        listState = listState,
                        onTransactionClick = { selectedTransaction = it }
                    )

                MainBottomNavDestination.SETTINGS ->
                    SettingsScreen(onBack = {}, showBackButton = false)
            }
        }
    }
}

package com.financetracker.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.financetracker.model.Transaction
import com.financetracker.ui.auth.LoginScreen
import com.financetracker.ui.dashboard.DashboardScreen
import com.financetracker.ui.main.MainScreen
import com.financetracker.ui.settings.SettingsScreen
import com.financetracker.ui.transaction.AddTransactionScreen
import com.financetracker.ui.transaction.TransactionDetailScreen
import com.financetracker.ui.transaction.TransactionListScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Main : Screen("main")
    object Dashboard : Screen("dashboard")
    object Transactions : Screen("transactions")
    object AddTransaction : Screen("add_transaction")
    object TransactionDetail : Screen("transaction_detail/{transactionId}") {
        const val ARG_TRANSACTION_ID = "transactionId"
        fun withId(id: Long): String = "transaction_detail/$id"
    }
    object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    isAuthenticated: Boolean,
    transactions: List<Transaction>
) {
    NavHost(
        navController = navController,
        startDestination = if (isAuthenticated) Screen.Main.route else Screen.Login.route
    ) {
        composable(Screen.Login.route) {
            LoginScreen(onLoginSuccess = { navController.navigate(Screen.Main.route) })
        }

        composable(Screen.Main.route) { MainScreen() }

        composable(Screen.Dashboard.route) {
            DashboardScreen(
                transactions = transactions,
                onAddTransaction = { navController.navigate(Screen.AddTransaction.route) }
            )
        }

        composable(Screen.Transactions.route) {
            TransactionListScreen(
                onTransactionClick = { navController.navigate(Screen.TransactionDetail.withId(it.id)) }
            )
        }

        composable(Screen.AddTransaction.route) {
            AddTransactionScreen(
                onSave = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.TransactionDetail.route,
            arguments = listOf(navArgument(Screen.TransactionDetail.ARG_TRANSACTION_ID) {
                type = NavType.LongType
            })
        ) { entry ->
            val id = entry.arguments?.getLong(Screen.TransactionDetail.ARG_TRANSACTION_ID)
            val transaction = transactions.firstOrNull { it.id == id }
            if (transaction != null) {
                TransactionDetailScreen(
                    transaction = transaction,
                    onBack = { navController.popBackStack() },
                    onDelete = { navController.popBackStack() }
                )
            } else {
                navController.popBackStack()
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

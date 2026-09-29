package com.financetracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.ui.AppViewModel
import com.financetracker.ui.auth.AuthGuard
import com.financetracker.ui.auth.AuthViewModel
import com.financetracker.ui.main.MainScreen
import com.financetracker.ui.theme.FinanceTrackerTheme
import com.financetracker.util.AppLocale
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    // The language picked in Settings is applied here: every string below is resolved against
    // the context this produces, so a change only takes effect by recreating the activity.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val appViewModel: AppViewModel = hiltViewModel()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()

            FinanceTrackerTheme(themeMode = themeMode) {
                AuthGuard(
                    authState = authViewModel.authState,
                    onUnauthenticated = {
                        val intent = Intent(context, LoginActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        context.startActivity(intent)
                        finish()
                    }
                ) {
                    MainScreen()
                }
            }
        }
    }
}
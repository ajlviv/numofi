package com.financetracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.ui.AppViewModel
import com.financetracker.ui.auth.LoginScreen
import com.financetracker.ui.theme.FinanceTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Launcher entry point. Auth state is owned by [LoginScreen], which also handles
 * the already-signed-in fast path by invoking [onLoginSuccess].
 */
@AndroidEntryPoint
class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()

            FinanceTrackerTheme(themeMode = themeMode) {
                LoginScreen(
                    onLoginSuccess = ::navigateToMain,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

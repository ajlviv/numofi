package com.financetracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.ui.AppViewModel
import com.financetracker.ui.auth.LoginScreen
import com.financetracker.ui.theme.FinanceTrackerTheme
import com.financetracker.ui.theme.isDarkTheme
import com.financetracker.util.AppLocale
import dagger.hilt.android.AndroidEntryPoint

/**
 * Launcher entry point. Auth state is owned by [LoginScreen], which also handles
 * the already-signed-in fast path by invoking [onLoginSuccess].
 */
@AndroidEntryPoint
class LoginActivity : ComponentActivity() {

    // Same contract as MainActivity: the language is applied before the first string resolves.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = isDarkTheme(themeMode)

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

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

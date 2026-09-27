package com.financetracker.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.financetracker.model.AuthState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AuthGuard(
    authState: StateFlow<AuthState>,
    onUnauthenticated: () -> Unit,
    content: @Composable () -> Unit
) {
    val currentState by authState.collectAsState()

    // Navigating is a side effect, so it must not run during composition.
    LaunchedEffect(currentState) {
        if (currentState is AuthState.Unauthenticated) {
            onUnauthenticated()
        }
    }

    when (currentState) {
        is AuthState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is AuthState.Authenticated -> content()

        is AuthState.Unauthenticated -> Unit
    }
}

package com.financetracker.ui.auth

import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import com.financetracker.model.AuthState
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom

private const val WEB_CLIENT_ID_PLACEHOLDER = "YOUR_"
private const val NONCE_BYTES = 32

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val isGoogleFlowInProgress by viewModel.isGoogleFlowInProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val webClientId = stringResource(R.string.default_web_client_id)
    val isConfigured = !webClientId.contains(WEB_CLIENT_ID_PLACEHOLDER)

    val credentialManager = remember(context) { CredentialManager.create(context) }
    val cancellationSignal = remember { CancellationSignal() }

    fun launchSignIn() {
        viewModel.onSignInLaunched()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(webClientId)
                    .setNonce(secureNonce())
                    .build()
            )
            .build()

        val callback = object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
            override fun onResult(result: GetCredentialResponse) {
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    runCatching { GoogleIdTokenCredential.createFrom(credential.data) }
                        .onSuccess(viewModel::signInWithGoogleId)
                        .onFailure(viewModel::onSignInFailed)
                } else {
                    viewModel.onSignInFailed(
                        IllegalStateException("Unexpected credential type: ${credential?.type}")
                    )
                }
            }

            override fun onError(e: GetCredentialException) {
                viewModel.onSignInFailed(e)
            }
        }

        credentialManager.getCredentialAsync(
            context = context,
            request = request,
            cancellationSignal = cancellationSignal,
            executor = ContextCompat.getMainExecutor(context),
            callback = callback
        )
    }

    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) onLoginSuccess()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.login_required),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = ::launchSignIn,
                enabled = isConfigured && !isGoogleFlowInProgress,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGoogleFlowInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.signing_in))
                } else {
                    Text(stringResource(R.string.sign_in))
                }
            }

            if (!isConfigured) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.web_client_id_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            val error = (authState as? AuthState.Unauthenticated)?.error
            if (error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Binds the returned ID token to this request, blocking replay of a stolen token. */
private fun secureNonce(): String {
    val bytes = ByteArray(NONCE_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

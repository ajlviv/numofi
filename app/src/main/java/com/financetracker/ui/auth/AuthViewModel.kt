package com.financetracker.ui.auth

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.R
import com.financetracker.model.AuthState
import com.financetracker.model.User
import com.financetracker.repository.AuthRepository
import com.financetracker.util.AppLocale
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isGoogleFlowInProgress = MutableStateFlow(false)
    val isGoogleFlowInProgress: StateFlow<Boolean> = _isGoogleFlowInProgress.asStateFlow()

    init {
        observeSession()
    }

    /**
     * Auth state follows the session reactively rather than being resolved once, so
     * signing out from anywhere (including Settings) tears the UI down immediately.
     * Loading remains transient because DataStore always emits.
     */
    private fun observeSession() {
        viewModelScope.launch {
            authRepository.currentUid.collect { uid ->
                if (uid == null) {
                    _authState.value = AuthState.Unauthenticated()
                    return@collect
                }

                _authState.value = AuthState.Loading
                val user = withTimeoutOrNull(SESSION_RESOLVE_TIMEOUT_MS) {
                    runCatching { authRepository.currentUser() }.getOrNull()
                }
                _authState.value = user
                    ?.let { AuthState.Authenticated(it) }
                    ?: AuthState.Unauthenticated()
            }
        }
    }

    fun onSignInLaunched() {
        _isGoogleFlowInProgress.value = true
        _authState.value = AuthState.Loading
    }

    /**
     * Accepts a Credential Manager [GoogleIdTokenCredential]. Its [GoogleIdTokenCredential.id]
     * is the stable uid for this OAuth client and is what all local data partitions by.
     */
    fun signInWithGoogleId(credential: GoogleIdTokenCredential) {
        _isGoogleFlowInProgress.value = false

        val uid = credential.id
        if (uid.isNullOrBlank()) {
            _authState.value = AuthState.Unauthenticated(string(R.string.auth_error_no_account_id))
            return
        }

        persistSignedInUser(
            User(
                uid = uid,
                // GoogleIdTokenCredential does not expose the email address; the uid is
                // what identifies the account locally.
                email = "",
                displayName = credential.displayName,
                photoUrl = credential.profilePictureUri?.toString()
            )
        )
    }

    fun onSignInFailed(throwable: Throwable?) {
        _isGoogleFlowInProgress.value = false

        if (throwable == null) {
            _authState.value = AuthState.Unauthenticated(string(R.string.auth_error_no_result))
            return
        }

        Log.e(TAG, "Credential Manager sign-in failed", throwable)
        _authState.value = AuthState.Unauthenticated(describe(throwable))
    }

    fun onSignInCancelled() {
        _isGoogleFlowInProgress.value = false
        _authState.value = AuthState.Unauthenticated()
    }

    /** Clears the session. The session observer then drives state to Unauthenticated. */
    fun signOut() {
        viewModelScope.launch {
            runCatching { authRepository.signOut() }
                .onFailure { Log.e(TAG, "Failed to clear the session", it) }
            _authState.value = AuthState.Unauthenticated()
        }
    }

    private fun persistSignedInUser(user: User) {
        viewModelScope.launch {
            runCatching { authRepository.signIn(user) }
                .onSuccess { _authState.value = AuthState.Authenticated(it) }
                .onFailure { throwable ->
                    Log.e(TAG, "Failed to persist the signed-in account", throwable)
                    _authState.value = AuthState.Unauthenticated(describe(throwable))
                }
        }
    }

    /**
     * Surfaces the real cause rather than a generic message, because Credential Manager
     * raises distinct types that call for different responses.
     *
     * Only the two fixed messages are translated: the branches below deliberately show what
     * the underlying exception said, which stays in whatever language the library produced.
     */
    private fun describe(throwable: Throwable): String = when (throwable) {
        is GetCredentialCancellationException -> string(R.string.auth_error_cancelled)
        is NoCredentialException -> string(R.string.auth_error_no_account_on_device)
        is GetCredentialException -> "Credential Manager error: ${throwable.message ?: throwable.type}"
        is ApiException -> "Google API error ${throwable.statusCode}: ${throwable.message}"
        else -> "${throwable::class.java.simpleName}: ${throwable.message ?: "no detail"}"
    }

    /**
     * Resolves a fixed message against the language picked in Settings: [AppLocale.wrap] is
     * what applies that pick, and the raw application context would answer in the device
     * language while the rest of the activity speaks the chosen one.
     */
    private fun string(@StringRes id: Int): String = AppLocale.wrap(context).getString(id)

    private companion object {
        const val TAG = "FinanceTrackerAuth"
        const val SESSION_RESOLVE_TIMEOUT_MS = 5_000L
    }
}

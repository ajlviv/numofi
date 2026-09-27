package com.financetracker.ui.auth

import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.model.AuthState
import com.financetracker.model.User
import com.financetracker.repository.AuthRepository
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
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
            _authState.value = AuthState.Unauthenticated(ERROR_NO_ACCOUNT_ID)
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
            _authState.value = AuthState.Unauthenticated(ERROR_NO_RESULT)
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
     */
    private fun describe(throwable: Throwable): String = when (throwable) {
        is GetCredentialCancellationException -> ERROR_CANCELLED
        is NoCredentialException -> ERROR_NO_ACCOUNT_ON_DEVICE
        is GetCredentialException -> "Credential Manager error: ${throwable.message ?: throwable.type}"
        is ApiException -> "Google API error ${throwable.statusCode}: ${throwable.message}"
        else -> "${throwable::class.java.simpleName}: ${throwable.message ?: "no detail"}"
    }

    private companion object {
        const val TAG = "FinanceTrackerAuth"
        const val SESSION_RESOLVE_TIMEOUT_MS = 5_000L
        const val ERROR_NO_ACCOUNT_ID = "Google account has no id"
        const val ERROR_NO_RESULT = "Google sign-in returned no result"
        const val ERROR_NO_ACCOUNT_ON_DEVICE =
            "No Google account was returned. Check that Play Services is up to date and an account is added."
        const val ERROR_CANCELLED = "Sign-in was cancelled."
    }
}

package com.financetracker.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * Persists which local account is signed in. Only the Google account uid is stored,
 * not any credential, so plain DataStore is appropriate here.
 */
@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val SIGNED_IN_UID = stringPreferencesKey("signed_in_uid")
    }

    val signedInUid: Flow<String?> = context.sessionDataStore.data
        .catch { throwable ->
            // A corrupt/unreadable file must not wedge the app in a loading state.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs[Keys.SIGNED_IN_UID]?.takeIf { it.isNotBlank() } }

    suspend fun signIn(uid: String) {
        context.sessionDataStore.edit { prefs -> prefs[Keys.SIGNED_IN_UID] = uid }
    }

    suspend fun signOut() {
        context.sessionDataStore.edit { prefs -> prefs.remove(Keys.SIGNED_IN_UID) }
    }
}

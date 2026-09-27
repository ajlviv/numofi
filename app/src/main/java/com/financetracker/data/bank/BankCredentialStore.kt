package com.financetracker.data.bank

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores bank access tokens encrypted at rest, backed by the Android Keystore
 * (AES256-GCM for values, AES256-SIV for keys).
 *
 * Tokens are per-bank so switching providers in Settings does not clobber the
 * credential for the previous one.
 */
@Singleton
class BankCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePersonalToken(bankId: String, token: String) {
        prefs.edit().putString(tokenKey(bankId), token).apply()
    }

    fun personalToken(bankId: String): String? =
        prefs.getString(tokenKey(bankId), null)?.takeIf { it.isNotBlank() }

    fun clear(bankId: String) {
        prefs.edit().remove(tokenKey(bankId)).apply()
    }

    /** Resolves the stored credential for [bankId], or null if none is saved. */
    fun authFor(bankId: String): BankAuth? =
        personalToken(bankId)?.let { BankAuth.PersonalToken(it) }

    private fun tokenKey(bankId: String) = "token_$bankId"

    private companion object {
        const val FILE_NAME = "bank_credentials"
    }
}

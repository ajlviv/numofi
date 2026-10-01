package com.financetracker.security

import androidx.annotation.StringRes
import com.financetracker.R

/**
 * What the device can ask the user for, decided from two answers rather than one.
 *
 * [BiometricManager.canAuthenticate] is asked twice — once for biometrics, once for the device
 * credential — because the two answers combine into four states that mean different things to
 * the user, and a single query would collapse the two cases they most need told apart: a device
 * with only a PIN is not a device whose fingerprint stopped working.
 *
 * The Android types are kept out of this file on purpose. It takes two plain booleans and
 * returns what the settings section has to say, so the only part of the app lock with real logic
 * in it is testable on a plain JVM with no emulator — which matters more here than usual,
 * because the `BiometricPrompt` call that consumes it cannot be exercised off-device at all.
 */
sealed interface LockCapability {

    /** Whether this capability can satisfy [com.financetracker.security.BiometricGate] at all. */
    val canLock: Boolean

    /** Names what would be asked for. Never names a modality it cannot confirm. */
    @get:StringRes
    val descriptionRes: Int

    /** A fingerprint or a face is enrolled, and nothing else. */
    data object BiometricOnly : LockCapability {
        override val canLock: Boolean get() = true
        override val descriptionRes: Int get() = R.string.settings_app_lock_biometric
    }

    /** A PIN, pattern or password is set, and no biometric is enrolled. */
    data object DeviceCredentialOnly : LockCapability {
        override val canLock: Boolean get() = true
        override val descriptionRes: Int get() = R.string.settings_app_lock_device_credential
    }

    /**
     * Both are enrolled.
     *
     * A separate case rather than "biometric, or else a PIN" applied to each, because on a device
     * with both the prompt shows the biometric first and falls back only after a failure. Saying
     * the two alternatives would be true but would lead the user to expect a prompt the system
     * does not offer.
     */
    data object Both : LockCapability {
        override val canLock: Boolean get() = true
        override val descriptionRes: Int get() = R.string.settings_app_lock_both
    }

    /**
     * Nothing is enrolled, so there is no prompt the app could put up.
     *
     * The lock cannot be turned on in this state. It is reported rather than enabled-and-then
     * refused at launch, which would look like the app had broken.
     */
    data object None : LockCapability {
        override val canLock: Boolean get() = false
        override val descriptionRes: Int get() = R.string.settings_app_lock_none
    }

    companion object {
        /**
         * [biometricAvailable] and [deviceCredentialAvailable] are the two `canAuthenticate`
         * answers, as plain booleans.
         *
         * Called on demand when the section is composed rather than cached at launch: a
         * capability cannot change while the screen is up, and a value read at startup would be
         * able to disagree with what the device reports by the time the user looks at it.
         */
        fun of(
            biometricAvailable: Boolean,
            deviceCredentialAvailable: Boolean
        ): LockCapability = when {
            biometricAvailable && deviceCredentialAvailable -> Both
            biometricAvailable -> BiometricOnly
            deviceCredentialAvailable -> DeviceCredentialOnly
            else -> None
        }
    }
}
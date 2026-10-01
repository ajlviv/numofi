package com.financetracker.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.financetracker.R

/**
 * Lets a screen announce that it is about to launch a picker.
 *
 * Null when there is no gate above — the settings screen rendered outside one, in a preview or a
 * test — in which case announcing is skipped and a stray stop re-locks as normal.
 */
val LocalExternalActivityLaunch = staticCompositionLocalOf<ExternalActivityLaunch?> { null }

/**
 * What the gate is doing, and therefore whether the content behind it may exist.
 *
 * The state is what [BiometricGate] switches on rather than a `visible` flag applied to the
 * content, because the point of the lock is that a balance is never laid out while the app is
 * locked. Composition, not visibility, is what would leak it.
 */
sealed interface AppLockState {

    /** The stored flag is off. Content renders and no prompt is ever built. */
    data object Disabled : AppLockState

    /**
     * The flag is on but the device has nothing enrolled any more — the user deleted their
     * fingerprint from the device's own settings after turning the lock on.
     *
     * Content renders. A lock with no credential behind it has two options and both are wrong:
     * refuse forever and the app is unreachable, or present a protection it cannot deliver.
     * Rendering is the honest one, and the settings section shows the switch disabled with the
     * reason, so the weakened state is visible rather than silent.
     */
    data object Unavailable : AppLockState

    /** The flag is on, something can satisfy it, and this resume has not authenticated. */
    data object Locked : AppLockState

    /** Authenticated. Content renders until the activity stops. */
    data object Unlocked : AppLockState
}

/**
 * Requires the device's own credential before the app shows anything.
 *
 * Sits above the content in the tree rather than inside it, so [content] is not composed at all
 * while the app is locked — no balance is measured, laid out or captured for a single frame.
 *
 * `enabled` is passed in rather than read here, so the caller owns the preference flow and the
 * gate stays free of the repository: the only thing it decides is what may be composed.
 *
 * Nullable for the cold start. DataStore reads asynchronously, so a `Boolean` defaulting to `false`
 * would compose the content behind the lock on every cold start and then hide it a moment later
 * when the stored `true` arrived — briefly, but with balances on screen. null means "not read
 * yet" and renders the placeholder, which is the safe direction to be wrong in.
 *
 * Note that a null value prompts even when the lock is in fact off, because the gate cannot tell
 * the two apart until the read lands. That is the one visible cost of the choice, and it lasts
 * as long as a DataStore read.
 */
@Composable
fun BiometricGate(
    enabled: Boolean?,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Re-read on every composition rather than remembered, so a fingerprint the user removes in
    // the device's settings is seen as removed on the next resume instead of on the next launch.
    val capability = lockCapability(context)

    // Saveable, so a rotation does not demand the credential again: a rotation is a
    // configuration change and not a backgrounding, and the sheet is torn down with the activity,
    // so a plain `remember` would re-prompt on every turn of the device. Safe to restore from a
    // saved instance state, because the one way to get here that way is process death — and
    // backgrounding fires ON_STOP first, which is what actually clears this.
    var unlocked by rememberSaveable { mutableStateOf(false) }

    // One instance per gate, not one per composition: the flag has to survive the recomposition
    // between `expect()` and the stop it is answering for.
    val externalLaunch = remember { ExternalActivityLaunch() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // ON_STOP rather than ON_PAUSE: the biometric sheet is itself a window over this
            // activity and pausing for it must not count as leaving. ON_STOP is what backgrounding
            // the app actually does, and what a rotation does not.
            //
            // A stop the app asked for — a document or folder picker — leaves the unlock alone.
            // See [ExternalActivityLaunch] for why this has to be announced rather than inferred.
            if (event == Lifecycle.Event.ON_STOP && !externalLaunch.consumeExpected()) {
                unlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = when {
        // Unread rather than off. DataStore emits on its first read, and a cold start would
        // otherwise compose the content behind the lock for as long as that read takes.
        enabled == null -> AppLockState.Locked
        !enabled -> AppLockState.Disabled
        !capability.canLock -> AppLockState.Unavailable
        unlocked -> AppLockState.Unlocked
        else -> AppLockState.Locked
    }

    // Provided around both branches, not just the content one: a picker is launched from wherever
    // the button is, and the announcement has to reach it through whatever is between.
    CompositionLocalProvider(LocalExternalActivityLaunch provides externalLaunch) {
        if (state is AppLockState.Locked) {
            // Bumped by the retry button and the sole key on this effect, so a retry is another
            // prompt and a failure — which changes nothing here — is not a prompt loop. A rejected
            // finger does not bump it: the system keeps its own sheet up and decides when to give up.
            var promptRequest by remember { mutableIntStateOf(0) }

            // Launched rather than called from the lifecycle observer because `authenticate()`
            // throws if the host is not resumed, and ON_START is not that.
            LaunchedEffect(promptRequest) {
                val host = context.findFragmentActivity() ?: return@LaunchedEffect
                BiometricPrompt(
                    host,
                    ContextCompat.getMainExecutor(host),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult
                        ) {
                            unlocked = true
                        }

                        // Nothing for onAuthenticationFailed: a rejected finger is not an ending.
                        // Nor for ERROR_LOCKOUT_PERMANENT — device credential stays in the allowed
                        // set, and a lockout there belongs to the OS rather than to this app.
                    }
                ).authenticate(promptInfo(host))
            }

            LockedPlaceholder(onRetry = { promptRequest++ })
        } else {
            content()
        }
    }
}

/**
 * Asks the device what it can authenticate with.
 *
 * Two queries rather than one, because [LockCapability.of] needs both answers separately and a
 * single combined query could not tell a device that has only a PIN from one whose fingerprint
 * has been removed. The combined form is still what the prompt asks with — [PROMPT_AUTHENTICATORS].
 */
fun lockCapability(context: Context): LockCapability {
    val manager = BiometricManager.from(context)
    return LockCapability.of(
        biometricAvailable = manager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS,
        deviceCredentialAvailable = manager.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    )
}

/**
 * Biometrics at **weak** class, plus the device credential.
 *
 * Weak rather than strong deliberately: face unlock on most Android devices is Class 2, so
 * requiring strong would exclude nearly every user who has only a face enrolled. That is a real
 * weakening of what the biometric path provides, and it is stated here rather than left to look
 * like hardware-class protection the app does not have.
 *
 * `DEVICE_CREDENTIAL` is in the set for two reasons: it is the fallback that keeps a device with
 * only a weak biometric usable, and it is what the library requires on API 28 and below, where
 * device credential is only supported combined with a biometric class.
 */
private const val PROMPT_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

private fun promptInfo(activity: FragmentActivity) = BiometricPrompt.PromptInfo.Builder()
    .setTitle(activity.getString(R.string.settings_app_lock_prompt_title))
    .setSubtitle(activity.getString(R.string.settings_app_lock_prompt_subtitle))
    .setAllowedAuthenticators(PROMPT_AUTHENTICATORS)
    .build()

/**
 * What a locked app shows.
 *
 * The app name and a retry, and nothing else — no balance, no transaction, no recents
 * thumbnail of one, because the content was never composed.
 *
 * The retry is not an escape. With device credential allowed, the system prompt is a
 * full-screen sheet that cannot be dismissed by a back button or an outside tap, so the only way
 * out of a locked app is the user's own credential. The button exists because a prompt that was
 * cancelled or failed has to leave the user able to ask again rather than at a dead end.
 */
@Composable
private fun LockedPlaceholder(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.settings_app_lock_locked_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.settings_app_lock_locked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.settings_app_lock_unlock))
        }
    }
}

/**
 * The fragment host `BiometricPrompt` needs, or null when there is none.
 *
 * The Compose context is not always the activity — a preview, or a wrapped context in a test —
 * and `authenticate()` throws rather than degrading when handed something else, so the cast is
 * checked and a missing host renders the placeholder without a prompt rather than crashing the
 * app on launch.
 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
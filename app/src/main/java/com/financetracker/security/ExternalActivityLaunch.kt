package com.financetracker.security

/**
 * Marks a stop that the app caused on purpose.
 *
 * **The problem this exists for.** Every document and folder picker is another activity. When one
 * comes to the front ours is stopped, and when the user picks something we are started again — so
 * a lock that re-locks on every stop cannot tell the user leaving the app from the app asking the
 * system for a file, and re-prompts on the way back. The backup folder picker and the restore
 * picker both stop working: the gate unmounts `MainScreen`, the settings section that launched
 * the picker is gone, and the result has nowhere to land.
 *
 * A timeout would hide this rather than fix it — a user who leaves and comes back inside the
 * window would get in without authenticating, which is the one thing the lock exists to prevent.
 *
 * **The cost is that a picker site must say so.** A new `ActivityResultLauncher` that is not
 * announced will re-lock on return, and the symptom is that flow silently not working rather than
 * anything that looks like a security problem. Every launch site in this app is in
 * `SettingsScreen`, and each one wraps its `launch` in [expectExternalActivity].
 */
class ExternalActivityLaunch {

    private var expected = false

    /**
     * Call immediately before launching a picker.
     *
     * Not a counter: two overlapping pickers cannot happen, and a count that had to be matched
     * would need matching to be correct.
     */
    fun expect() {
        expected = true
    }

    /**
     * Whether the stop being handled was one this class asked for, clearing the flag.
     *
     * Consumed rather than read, so a stop that was not expected does not leave the next real one
     * unexamined.
     */
    internal fun consumeExpected(): Boolean {
        if (!expected) return false
        expected = false
        return true
    }
}
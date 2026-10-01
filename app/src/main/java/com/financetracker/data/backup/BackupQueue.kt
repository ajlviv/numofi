package com.financetracker.data.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

/**
 * Why a backup was asked for. Carried so the status line can say what triggered the last
 * upload, which is the difference between "backed up" and "backed up after you imported".
 */
enum class BackupReason {
    /** The user just turned backup on. */
    ENABLED,

    /** The user pressed the back-up button. */
    MANUAL,

    /** A statement file was imported. */
    IMPORT,

    /** A bank sync finished. */
    BANK_SYNC,

    /** A transaction was added, changed or removed by hand. */
    TRANSACTION,

    /** A bond trade was recorded. */
    BOND_TRADE,

    /** A bank was added, renamed, archived or moved. */
    BANK,

    /** A recurring payment schedule was added, changed, archived or removed. */
    RECURRING
}

/**
 * Turns any number of requests into the fewest possible uploads.
 *
 * A statement import inserts its rows one at a time and a bond trade writes three tables, so
 * the calls that ask for a backup arrive in bursts. A conflated channel with one consumer is
 * what collapses a burst: a request made while an upload is in flight replaces the one
 * waiting behind it, so a thousand rows become at most two uploads — the one already running
 * and one after it, which picks up everything the first one missed.
 *
 * There is no debounce delay. The place to absorb a burst is the channel, and a timer would
 * only add a window during which the app could be killed with the change unsaved.
 */
internal class BackupQueue(
    scope: CoroutineScope,
    private val perform: suspend (BackupReason) -> Unit
) {
    private val pending = Channel<BackupReason>(Channel.CONFLATED)

    init {
        scope.launch {
            for (reason in pending) {
                try {
                    perform(reason)
                } catch (cancellation: CancellationException) {
                    // The scope going away is not an upload failure and must not be eaten.
                    throw cancellation
                } catch (_: Exception) {
                    // One failed upload must not take the consumer down for the rest of the
                    // process, or the next request would sit in the channel forever.
                }
            }
        }
    }

    /**
     * Asks for an upload, and returns without waiting for one.
     *
     * Never suspends and never fails, because every caller is a write path that has already
     * done its job and has nothing useful to do with a backup it cannot run right now.
     */
    fun request(reason: BackupReason) {
        pending.trySend(reason)
    }
}

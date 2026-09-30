package com.financetracker.backup

import com.financetracker.data.backup.BackupQueue
import com.financetracker.data.backup.BackupReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The queue is the only piece of real logic in the backup feature, and the reason it exists
 * is that one import can insert hundreds of rows. These tests pin what a burst of those
 * requests has to add up to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupQueueTest {

    /**
     * The consumer parks on an empty channel for as long as the app lives, so each test gets
     * a scope it owns and tears down. Handing it the [TestScope] instead would leave
     * `runTest` waiting on a coroutine that is never going to finish.
     */
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
    }

    private fun TestScope.newQueue(perform: suspend (BackupReason) -> Unit): BackupQueue {
        val own = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scope = own
        return BackupQueue(own, perform)
    }

    @Test
    fun `requests made while an upload is running collapse into one more`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val uploads = mutableListOf<BackupReason>()
        val queue = newQueue { reason ->
            uploads += reason
            if (uploads.size == 1) gate.await()
        }

        queue.request(BackupReason.IMPORT)
        runCurrent()
        assertEquals(listOf(BackupReason.IMPORT), uploads)

        repeat(10) { queue.request(BackupReason.BANK_SYNC) }
        runCurrent()
        // The first upload is still parked, so nothing new has started.
        assertEquals(listOf(BackupReason.IMPORT), uploads)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(BackupReason.IMPORT, BackupReason.BANK_SYNC), uploads)
    }

    @Test
    fun `the reason carried by the follow-up is the last one requested`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val uploads = mutableListOf<BackupReason>()
        val queue = newQueue { reason ->
            uploads += reason
            if (uploads.size == 1) gate.await()
        }

        queue.request(BackupReason.IMPORT)
        runCurrent()
        queue.request(BackupReason.IMPORT)
        queue.request(BackupReason.BOND_TRADE)
        gate.complete(Unit)
        runCurrent()

        // The later request is the better description of what changed, so it is the one kept.
        assertEquals(BackupReason.BOND_TRADE, uploads[1])
    }

    @Test
    fun `a request made once everything is idle starts its own upload`() = runTest {
        val uploads = mutableListOf<BackupReason>()
        val queue = newQueue { uploads += it }

        queue.request(BackupReason.TRANSACTION)
        runCurrent()
        queue.request(BackupReason.BANK)
        runCurrent()

        assertEquals(listOf(BackupReason.TRANSACTION, BackupReason.BANK), uploads)
    }

    @Test
    fun `an upload that throws does not stop the ones after it`() = runTest {
        val uploads = mutableListOf<BackupReason>()
        val queue = newQueue { reason ->
            uploads += reason
            if (reason == BackupReason.IMPORT) throw IllegalStateException("no folder")
        }

        queue.request(BackupReason.IMPORT)
        runCurrent()
        queue.request(BackupReason.BANK_SYNC)
        runCurrent()

        // A Drive that is unreachable must not silently stop every later backup.
        assertEquals(listOf(BackupReason.IMPORT, BackupReason.BANK_SYNC), uploads)
    }
}

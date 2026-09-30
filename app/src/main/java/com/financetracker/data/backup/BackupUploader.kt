package com.financetracker.data.backup

import android.net.Uri
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.di.ApplicationScope
import com.financetracker.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.CancellationException as JavaCancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the local data into the user's Drive folder whenever something changes it.
 *
 * The one thing every caller needs to know is [requestUpload], and it deliberately tells them
 * nothing else: where the folder is, whether backup is switched on and who is signed in are all
 * resolved here, at the moment the upload actually runs. A write path therefore calls it
 * unconditionally and never has to find out whether backup is on.
 *
 * Runs on an application scope rather than a `viewModelScope`, because the writes that trigger
 * it outlive the screen that caused them. The cost is that an upload still in flight is lost
 * if the process dies, which is accepted: the data being backed up is on the device, so the
 * worst case is a backup that is one change out of date.
 */
@Singleton
class BackupUploader @Inject constructor(
    private val exporter: BackupExporter,
    private val codec: BackupSnapshotCodec,
    private val store: BackupStore,
    private val settings: SettingsRepository,
    private val session: SessionRepository,
    @ApplicationScope scope: CoroutineScope
) : BackupRequests {

    /** Kept apart from [status] so the settings are the only thing that decides "off". */
    private enum class Run { IDLE, RUNNING, FAILED }

    private val run = MutableStateFlow(Run.IDLE)

    private val queue = BackupQueue(scope, ::upload)

    val status: StateFlow<BackupStatus> =
        combine(
            settings.backupTreeUri,
            settings.lastBackupAt,
            run
        ) { tree, lastUploadedAt, state ->
            when {
                tree == null -> BackupStatus.Off
                state == Run.RUNNING -> BackupStatus.Running
                state == Run.FAILED -> BackupStatus.Failed(lastUploadedAt)
                else -> BackupStatus.Idle(lastUploadedAt)
            }
        }.stateIn(scope, SharingStarted.Eagerly, BackupStatus.Off)

    /**
     * Asks for the data to be copied, and returns immediately.
     *
     * The [reason] is what the status line reports, so an upload can say it followed an
     * import rather than only that it happened.
     */
    override fun requestUpload(reason: BackupReason) {
        queue.request(reason)
    }

    private suspend fun upload(reason: BackupReason) {
        val tree = settings.backupTreeUri.first() ?: return
        val uid = session.signedInUid.first() ?: return

        val now = System.currentTimeMillis()
        try {
            run.value = Run.RUNNING
            val snapshot = exporter.snapshot(uid, now)
            store.write(Uri.parse(tree), FILE_NAME, codec.encode(snapshot))
            settings.setBackupUploadedAt(now)
            run.value = Run.IDLE
        } catch (cancellation: CancellationException) {
            // The app going away is not a failed backup, and swallowing it here would leave
            // the status reading "running" for a process that no longer exists.
            throw cancellation
        } catch (cancellation: JavaCancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Nothing is retried. A Drive that is unreachable leaves the next backup slightly
            // stale, which is the right thing to do with a copy of data still on the device.
            run.value = Run.FAILED
        }
    }

    companion object {
        /**
         * One file, overwritten on every upload.
         *
         * Not a rolling set of dated files, and not a list of past snapshots inside one file:
         * either would mean reading the old file before replacing it, which is a full download
         * on every upload. Drive keeps its own revision history of a document it syncs, so
         * there is already a recovery point behind a bad import.
         */
        const val FILE_NAME = "finance-tracker-backup.json"
    }
}

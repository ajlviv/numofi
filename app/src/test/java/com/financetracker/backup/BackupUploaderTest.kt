package com.financetracker.backup

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.AppDatabase
import com.financetracker.data.backup.BackupExporter
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupSnapshot
import com.financetracker.data.backup.BackupSnapshotCodec
import com.financetracker.data.backup.BackupStatus
import com.financetracker.data.backup.BackupStore
import com.financetracker.data.backup.BackupUploader
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [BackupUploader] against a real database, real preferences and a fake folder.
 *
 * The folder is the only thing faked. Everything above it — which rows are read, what the file
 * says, whether an upload counts — is the real thing, because what could go wrong there is
 * exactly the part a mock would agree with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupUploaderTest {

    private class RecordingStore : BackupStore {
        val writes = mutableListOf<Pair<Uri, String>>()
        var lastContents: String? = null
        var failure: Exception? = null

        override fun write(tree: Uri, name: String, contents: String) {
            failure?.let { throw it }
            writes += tree to name
            lastContents = contents
        }

        // Grant handling belongs to the system, not to anything the uploader does.
        override fun persist(tree: Uri) = error("unused")
        override fun release(tree: Uri) = error("unused")
        override fun read(document: Uri): String = error("unused")
    }

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var session: SessionRepository
    private lateinit var scope: CoroutineScope
    private lateinit var store: RecordingStore
    private lateinit var uploader: BackupUploader

    private val tree = Uri.parse("content://com.google.android.apps.docs/tree/backup-folder")
    private val uid = "uid-1"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context)
        session = SessionRepository(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = RecordingStore()
        resetStoredState()
        uploader = newUploader()
    }

    /**
     * Both preference files are a single `DataStore` for the whole JVM — each delegate is a
     * top-level property — so one test's folder or signed-in account would otherwise be the
     * next test's starting point. Cleared here rather than by a test-only setter; zero reads
     * as "never" because the repository only reports a timestamp greater than zero.
     */
    private fun resetStoredState() = runBlocking {
        settings.setBackupTreeUri(null)
        settings.setBackupUploadedAt(0)
        session.signOut()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun newUploader() = BackupUploader(
        exporter = BackupExporter(db.transactionDao(), db.bankDao(), db.bondDao(), db.recurringPaymentDao()),
        codec = BackupSnapshotCodec(),
        store = store,
        settings = settings,
        session = session,
        scope = scope
    )

    private suspend fun enable() {
        session.signIn(uid)
        settings.setBackupTreeUri(tree.toString())
    }

    /** Waits for the background upload to finish, and fails the test if it never does. */
    private suspend fun awaitWrites(count: Int) {
        withTimeout(5_000) {
            while (store.writes.size < count) delay(10)
        }
    }

    private suspend fun storeTransaction(title: String) = db.transactionDao().insert(
        TransactionEntity(
            userId = uid,
            title = title,
            amount = 5.0,
            type = TransactionType.EXPENSE,
            category = "other"
        )
    )

    // runBlocking rather than runTest throughout: the queue consumes on a real scope and
    // preferences are read off disk, so a virtual-time test body would never be resumed by the
    // thread that finishes the work.

    @Test
    fun `the status is off until a folder has been picked`() = runBlocking {
        assertEquals(BackupStatus.Off, uploader.status.value)
    }

    @Test
    fun `a request with no folder picked writes nothing`() = runBlocking {
        session.signIn(uid)

        uploader.requestUpload(BackupReason.IMPORT)
        delay(300)

        assertEquals(emptyList<Pair<Uri, String>>(), store.writes)
    }

    @Test
    fun `a request with nobody signed in writes nothing`() = runBlocking {
        settings.setBackupTreeUri(tree.toString())

        uploader.requestUpload(BackupReason.IMPORT)
        delay(300)

        assertEquals(emptyList<Pair<Uri, String>>(), store.writes)
    }

    @Test
    fun `a request writes one file into the picked folder`() = runBlocking {
        enable()
        storeTransaction("Кава")

        uploader.requestUpload(BackupReason.IMPORT)
        awaitWrites(1)

        // Named for what is in it. A `.json` holding a gzip stream misleads the one thing a
        // file name is for, which is telling the user what they are looking at in Drive.
        assertEquals(listOf(tree to "finance-tracker-backup.json.gz"), store.writes)
    }

    @Test
    fun `what the uploader hands over is still readable json`() = runBlocking {
        enable()
        storeTransaction("Кава")

        uploader.requestUpload(BackupReason.IMPORT)
        awaitWrites(1)

        // Compression is the store's business, so everything above this line — and every test
        // in the suite — still sees the export as text. If this ever needs a decompressor to
        // read, the seam between "what a backup is" and "how it is stored" has leaked.
        val contents = store.lastContents!!
        assertTrue(contents.trimStart().startsWith("{"))
        assertEquals(listOf("Кава"), BackupSnapshotCodec().decode(contents).transactions.map { it.title })
    }

    @Test
    fun `the file holds the signed-in account's data`() = runBlocking {
        enable()
        storeTransaction("Кава")
        db.transactionDao().insert(
            TransactionEntity(
                userId = "uid-2",
                title = "Someone else's coffee",
                amount = 5.0,
                type = TransactionType.EXPENSE,
                category = "other"
            )
        )

        uploader.requestUpload(BackupReason.IMPORT)
        awaitWrites(1)

        val snapshot = BackupSnapshotCodec().decode(store.lastContents!!)
        assertEquals(uid, snapshot.uid)
        assertEquals(listOf("Кава"), snapshot.transactions.map { it.title })
    }

    @Test
    fun `a successful upload records when it happened`() = runBlocking {
        enable()

        uploader.requestUpload(BackupReason.ENABLED)
        awaitWrites(1)
        val stored = awaitStatus { it is BackupStatus.Idle && it.lastUploadedAt != null }

        assertTrue((stored as BackupStatus.Idle).lastUploadedAt!! > 0L)
    }

    @Test
    fun `a folder that cannot be written to is reported as a failure`() = runBlocking {
        enable()
        store.failure = SecurityException("permission revoked")

        uploader.requestUpload(BackupReason.IMPORT)
        val status = awaitStatus { it is BackupStatus.Failed }

        // A Drive that has gone away is something the user has to be told about, and the time
        // of the last good backup has to stay where it was rather than move to now.
        assertEquals(null, (status as BackupStatus.Failed).lastUploadedAt)
        assertEquals(emptyList<Pair<Uri, String>>(), store.writes)
    }

    @Test
    fun `a later request still runs after a failure`() = runBlocking {
        enable()
        store.failure = SecurityException("permission revoked")
        uploader.requestUpload(BackupReason.IMPORT)
        awaitStatus { it is BackupStatus.Failed }

        store.failure = null
        uploader.requestUpload(BackupReason.BANK_SYNC)
        awaitWrites(1)

        assertEquals(BackupStatus.Idle::class, awaitStatus { it !is BackupStatus.Running }::class)
    }

    private suspend fun awaitStatus(predicate: (BackupStatus) -> Boolean): BackupStatus {
        withTimeout(5_000) {
            while (!predicate(uploader.status.value)) delay(10)
        }
        return uploader.status.value
    }
}

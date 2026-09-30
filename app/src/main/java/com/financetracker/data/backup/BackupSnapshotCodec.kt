package com.financetracker.data.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the backup file's JSON.
 *
 * One place that owns the format, so that the reader a future restore will use is the exact
 * inverse of the writer used here rather than a second guess at it.
 *
 * Pretty printed on purpose. The file is the user's, in their own Drive, and a wall of escaped
 * single-line JSON is not something they can do anything with; the size difference is
 * irrelevant next to that.
 *
 * Nulls are left out, which is Gson's default and is not a loss: a reader treats a missing
 * value as a null one, and every field in [BackupSnapshot] is nullable or has a value that a
 * row written by this app always carries.
 */
@Singleton
class BackupSnapshotCodec @Inject constructor() {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    fun encode(snapshot: BackupSnapshot): String = gson.toJson(snapshot)

    /**
     * Reads a file written by this or an earlier build.
     *
     * A file from a newer format is refused rather than read as far as it happens to match.
     * The alternative is the worst failure this feature has: a snapshot that decodes cleanly,
     * restores without an error, and has silently dropped whatever the newer build added —
     * so the user believes they have recovered their data and has not.
     *
     * A v1 file needs nothing special. It repeats the account on every transaction and
     * [TransactionRow] has nowhere to put it, so Gson drops the field and the account comes
     * from the root instead — which is where it already said the same thing.
     *
     * The two failures a caller has to tell apart are a file it cannot read at all
     * ([UnreadableBackupException]) and one written by a newer app ([UnsupportedFormatException]).
     * They are separate because the user's next move differs: find the right file, or update
     * the app. Anything Gson throws is wrapped in the first, so no caller has to know that
     * Gson's exceptions are not [IllegalArgumentException]s.
     *
     * @throws UnsupportedFormatException if the file is from a newer build than this one.
     * @throws UnreadableBackupException if [json] is not a snapshot document. A caller that
     *   finds a file it cannot read has to say so rather than treat it as an empty backup.
     */
    fun decode(json: String): BackupSnapshot {
        val snapshot = try {
            gson.fromJson(json, BackupSnapshot::class.java)
        } catch (e: JsonParseException) {
            throw UnreadableBackupException(e)
        } ?: throw UnreadableBackupException()

        if (snapshot.format > BackupSnapshot.FORMAT) {
            throw UnsupportedFormatException(snapshot.format)
        }
        return snapshot
    }
}

/**
 * The file is not a backup this app can read: it is not JSON, it is empty, or it is missing
 * the fields a snapshot has.
 */
class UnreadableBackupException(cause: Throwable? = null) : IllegalArgumentException(
    "This file is not a FinanceTracker backup.",
    cause
)

/**
 * The file was written by a newer build than this one.
 *
 * Carries the number it found, because "your backup is from a newer version of the app" is a
 * sentence the user can act on and "restore failed" is not.
 */
class UnsupportedFormatException(val found: Int) : IllegalArgumentException(
    "This backup was written by a newer version of the app (format $found, this build reads " +
        "up to ${BackupSnapshot.FORMAT}). Update the app and try again."
)

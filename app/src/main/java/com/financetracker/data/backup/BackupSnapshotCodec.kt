package com.financetracker.data.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
     * @throws com.google.gson.JsonSyntaxException if [json] is not a snapshot document. A
     *   caller that finds a file it cannot read has to say so rather than treat it as an
     *   empty backup.
     */
    fun decode(json: String): BackupSnapshot =
        gson.fromJson(json, BackupSnapshot::class.java)
            ?: throw IllegalArgumentException("The backup file is empty")
}

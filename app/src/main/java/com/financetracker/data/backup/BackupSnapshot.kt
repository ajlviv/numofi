package com.financetracker.data.backup

import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.TransactionEntity

/**
 * One copy of the data, as it goes into the backup file.
 *
 * A logical export of the rows rather than a copy of the SQLite file, and that is the whole
 * reason. The database has no upgrade path at all — `AppDatabase` ships no migrations and no
 * destructive fallback — so a file copied from a device would be a file only its own schema
 * version can open, and restoring it after a schema change would make Room refuse rather than
 * migrate. A snapshot of rows is independent of both the schema version and the journal
 * mode, and it is a JSON document the user can read without this app.
 *
 * The Room entities are stored as they are. Nothing is folded, normalised or recomputed on the
 * way out: `searchText` in particular is baked in at write time and embeds the bank display
 * name, so rebuilding it here would rewrite a field the whole app deliberately never
 * rewrites, and the restored rows would stop matching the names their own bank rows now carry.
 *
 * What is *not* here is the `users` table. The profile row is re-fetched at sign-in, so
 * carrying it would put an account identity into a file the user may share for no gain.
 *
 * [bondTrades] and [banks] and [bonds] are device-global — the schema gives them no uid — so
 * they cannot be split per account and are carried whole. A snapshot therefore holds every
 * bond position on the device, including any belonging to a second Google account signed in on
 * the same install.
 */
data class BackupSnapshot(
    /** See [FORMAT]. Only ever incremented, never reused for different content. */
    val format: Int,
    /** Which build wrote this, so a future restore can say what produced the file. */
    val appVersion: String,
    /** Epoch millis the snapshot was taken. */
    val createdAt: Long,
    /** The account the transactions belong to, see [com.financetracker.model.TransactionEntity.userId]. */
    val uid: String,
    val transactions: List<TransactionEntity>,
    val banks: List<BankEntity>,
    val bonds: List<BondEntity>,
    /**
     * Oldest first, because that is the order a position is folded in and a restore that
     * reversed it would produce a different cost basis for the same trades.
     */
    val bondTrades: List<BondTradeEntity>
) {
    companion object {
        /**
         * The version of the file layout.
         *
         * Not the same number as anything in the database, and not tied to it. A reader
         * decides what it can do with a file by this number alone, which is what lets the
         * columns of a table change without invalidating every backup ever taken.
         */
        const val FORMAT = 1
    }
}

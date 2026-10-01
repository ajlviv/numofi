package com.financetracker.data.backup

import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity

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
 * The rows are stored as they are. Nothing is folded, normalised or recomputed on the way
 * out: `searchText` in particular is baked in at write time and embeds the bank display
 * name, so rebuilding it here would rewrite a field the whole app deliberately never
 * rewrites, and the restored rows would stop matching the names their own bank rows now carry.
 *
 * What is *not* here is the `users` table. The profile row is re-fetched at sign-in, so
 * carrying it would put an account identity into a file the user may share for no gain.
 *
 * The account is stated once, at [uid]. Transactions are [TransactionRow] and recurring
 * schedules are [RecurringPaymentRow], so that neither has anywhere to repeat it; banks, bonds
 * and trades have no account column to begin with.
 *
 * [bondTrades] and [banks] and [bonds] are device-global — the schema gives them no uid — so
 * they cannot be split per account and are carried whole. A snapshot therefore holds every
 * bond position on the device, including any belonging to a second Google account signed in on
 * the same install. A restore has to decide what to do about that, and refusing is the safe
 * answer: see [BackupRestorer]. [recurringPayments], by contrast, is per-account like
 * transactions and is carried for [uid] alone.
 */
data class BackupSnapshot(
    /** See [FORMAT]. Only ever incremented, never reused for different content. */
    val format: Int,
    /** Which build wrote this, so a future restore can say what produced the file. */
    val appVersion: String,
    /** Epoch millis the snapshot was taken. */
    val createdAt: Long,
    /** The account every transaction in this file belongs to. */
    val uid: String,
    val transactions: List<TransactionRow>,
    val banks: List<BankEntity>,
    val bonds: List<BondEntity>,
    /**
     * Oldest first, because that is the order a position is folded in and a restore that
     * reversed it would produce a different cost basis for the same trades.
     */
    val bondTrades: List<BondTradeEntity>,

    /**
     * Recurring payment schedules, or null for a file written before format 3.
     *
     * Nullable rather than defaulted to empty because Gson does not honour Kotlin property
     * defaults: a v1 or v2 file has no such key, so the field would be set to null whatever
     * default were written here. Callers read it through `orEmpty()`; see [BackupRestorer].
     */
    val recurringPayments: List<RecurringPaymentRow>? = null
) {
    companion object {
        /**
         * The version of the file layout.
         *
         * Not the same number as anything in the database, and not tied to it. A reader
         * decides what it can do with a file by this number alone, which is what lets the
         * columns of a table change without invalidating every backup ever taken.
         *
         * 1 — the first layout: [TransactionEntity] verbatim, so every transaction repeated
         *     the account the root already carried.
         * 2 — transactions are [TransactionRow] and carry no account of their own.
         * 3 — recurring payment schedules are carried as [RecurringPaymentRow].
         *
         * Only ever raised, and a file whose number is above this one is refused rather than
         * read as far as it happens to match: a newer build may have moved a field, and a
         * half-understood file is worse than a refused one.
         */
        const val FORMAT = 3

        /** The lowest format this build can read. */
        const val OLDEST_READABLE_FORMAT = 1
    }
}

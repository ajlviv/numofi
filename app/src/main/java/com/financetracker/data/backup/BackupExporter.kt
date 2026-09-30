package com.financetracker.data.backup

import com.financetracker.BuildConfig
import com.financetracker.data.BankDao
import com.financetracker.data.BondDao
import com.financetracker.data.TransactionDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the rows that belong in a backup, see [BackupSnapshot] for what the file is and why.
 *
 * Split from the uploading so that deciding *what* is copied stays a question about the
 * database, and nothing here knows that the destination is a folder in Drive.
 */
@Singleton
class BackupExporter @Inject constructor(
    private val transactionDao: TransactionDao,
    private val bankDao: BankDao,
    private val bondDao: BondDao
) {

    /**
     * The data as of [now], for [uid].
     *
     * [now] is passed in rather than read from the clock so that the timestamp written into
     * the file is the same one recorded as the upload's time, and so a test can state it.
     */
    suspend fun snapshot(uid: String, now: Long): BackupSnapshot = BackupSnapshot(
        format = BackupSnapshot.FORMAT,
        appVersion = BuildConfig.VERSION_NAME,
        createdAt = now,
        uid = uid,
        // Scoped to the one account. The other tables have no uid column and are device
        // -wide, so they are read whole rather than filtered by a column that is not there.
        transactions = transactionDao.getAllForUser(uid).first(),
        banks = bankDao.getAllOnce(),
        bonds = bondDao.getBondsOnce(),
        bondTrades = bondDao.getTrades()
    )
}

package com.financetracker.testing

import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupRequests

/**
 * Records what a write path asked to be backed up, without uploading anything.
 *
 * Shared rather than copied per test for the same reason [FakeBankDao] is: every repository
 * that writes rows now takes a [BackupRequests], and each of them has a test class that would
 * otherwise have to invent its own.
 */
class FakeBackupRequests : BackupRequests {

    val reasons = mutableListOf<BackupReason>()

    override fun requestUpload(reason: BackupReason) {
        reasons += reason
    }

    val requested: Boolean get() = reasons.isNotEmpty()
}

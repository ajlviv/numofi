package com.financetracker.data.backup

/**
 * What the backup is doing, as the settings screen shows it.
 *
 * [Off] is not the same as "nothing has been uploaded yet": it means there is no folder, so
 * there is nothing to upload to. Keeping the two apart is what lets the screen say which of
 * them is true instead of showing an empty timestamp.
 */
sealed interface BackupStatus {

    /** No folder has been picked, or it was removed. */
    data object Off : BackupStatus

    /** Nothing in flight. [lastUploadedAt] is null until the first one succeeds. */
    data class Idle(val lastUploadedAt: Long?) : BackupStatus

    data object Running : BackupStatus

    /**
     * The last attempt could not be written.
     *
     * [lastUploadedAt] is still the last time one worked, deliberately: a failed attempt must
     * not move the date forward, or the screen would claim the backup is current when it is
     * not.
     */
    data class Failed(val lastUploadedAt: Long?) : BackupStatus
}

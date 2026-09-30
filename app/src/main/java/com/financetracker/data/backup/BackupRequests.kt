package com.financetracker.data.backup

/**
 * What a write path says when it has changed the data.
 *
 * The repositories and services that write rows depend on this rather than on
 * [BackupUploader], for two reasons. They have no business knowing where a backup goes — that
 * is the one decision this interface exists to hide — and an uploader is far too heavy a thing
 * to build in order to assert that a repository asked for one.
 *
 * Calling it is unconditional. Whether backup is switched on, and which folder it goes to, are
 * both resolved when the upload runs, so a write path never has to find out.
 */
interface BackupRequests {

    /**
     * Asks for the data to be copied, and returns immediately.
     *
     * Safe to call in a burst: requests that arrive while an upload is in flight collapse into
     * one extra upload rather than one each.
     */
    fun requestUpload(reason: BackupReason)
}

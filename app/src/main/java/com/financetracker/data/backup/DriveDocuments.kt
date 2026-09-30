package com.financetracker.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The documents provider the Google Drive app exposes, which is what makes Drive visible to
 * the system file picker at all.
 */
private const val DRIVE_AUTHORITY = "com.google.android.apps.drive.documents"

/** Drive's own root, which is the user's "My Drive". */
private const val DRIVE_ROOT_ID = "root"

/**
 * Where to open the folder picker.
 *
 * A picker launched with no initial location starts wherever the device decides, and on a
 * phone that is normally internal storage. For a feature whose only useful destination is
 * Drive, that is the wrong default and reads as though the app cannot see Drive at all.
 */
internal fun driveRootUri(): Uri = DocumentsContract.buildRootUri(DRIVE_AUTHORITY, DRIVE_ROOT_ID)

/**
 * The same URI, but only when Drive is actually installed to answer for it.
 *
 * Passing an initial location whose provider is missing is not something to do on faith:
 * DocumentsUI is entitled to show an empty tree, and the user would then be told the folder
 * could not be opened. Without a Drive app there is nothing to back up to anyway, and the
 * caller's fallback — launch the picker with no initial location — still lets them pick a
 * folder by hand if the file manager offers something usable.
 */
internal fun driveRootUriIfInstalled(context: Context): Uri? =
    if (context.driveDocumentsInstalled()) driveRootUri() else null

/**
 * Whether Drive's documents provider is registered on this device.
 *
 * Asks the package manager by authority rather than querying the URI, because a query
 * against a provider that is not installed throws, and a phone without the Drive app is a
 * normal state rather than an error. `resolveContentProvider` is looked up by the authority
 * a provider declares, which is the one thing the Drive app does declare.
 */
private fun Context.driveDocumentsInstalled(): Boolean =
    packageManager.resolveContentProvider(DRIVE_AUTHORITY, 0) != null

package com.financetracker.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a backup file is written.
 *
 * An interface so that [BackupUploader] can be exercised without a `DocumentsProvider` to
 * point it at — there is none on the JVM — while everything above this line stays real.
 *
 * Both directions take and return the backup as text, which keeps the encoding an
 * implementation detail of the one class that touches bytes: a fake store sees exactly what a
 * real one is asked to write, so a test can assert on the export without a provider or a
 * compressor. See [BackupEncoding].
 */
interface BackupStore {

    /**
     * Writes [contents] to [name] inside [tree], replacing it if it is already there.
     *
     * @throws IOException if the file cannot be created or opened.
     * @throws SecurityException if the grant to [tree] has been revoked.
     */
    fun write(tree: Uri, name: String, contents: String)

    /**
     * Reads a backup out of a single document the user picked, whether it is compressed or
     * not. The file may be one this app wrote years ago and never compressed, so the
     * encoding is sniffed rather than assumed.
     *
     * Takes a document rather than a tree and a name because this is the picker handing back
     * the file itself, not the folder this app writes into. The two are deliberately not the
     * same operation: the folder is where uploads land, and a restore from it would be limited
     * to the file this app wrote rather than the one the user actually means to restore.
     *
     * @throws IOException if the document cannot be opened or read.
     * @throws UnreadableBackupException if it is compressed but does not decode, or expands
     *   past [BackupEncoding.MAX_DECOMPRESSED_BYTES].
     * @throws SecurityException if the grant to [document] has been revoked.
     */
    fun read(document: Uri): String

    /**
     * Keeps read and write access to [tree] across restarts of the app.
     *
     * Without this the grant lasts until the process dies, and the user would be asked to pick
     * the folder again every time they opened the app.
     */
    fun persist(tree: Uri)

    /**
     * Gives the grant up, so the app stops holding a write path into the user's Drive.
     *
     * Called when backup is switched off: the grant outlives the choice to use it, and leaving
     * it held would be a permission nothing in the app is using.
     */
    fun release(tree: Uri)
}

/**
 * Writes the backup into a folder the user picked with the system folder picker.
 *
 * The Storage Access Framework rather than the Drive API, which buys a persisted write grant
 * to a folder in the user's own Drive with no OAuth scope, no consent screen, and nothing for
 * Google to verify. It also means the file is a document the user can open and delete
 * themselves, and it survives this app being uninstalled — which the Drive API's appdata
 * folder does not.
 *
 * The grant does not survive a reinstall, since the system drops it with the app. The
 * settings screen says so rather than letting the user find out from a failed backup.
 */
@Singleton
class SafBackupStore @Inject constructor(
    @ApplicationContext private val context: Context
) : BackupStore {

    private val resolver get() = context.contentResolver

    override fun write(tree: Uri, name: String, contents: String) {
        val existing = findDocument(tree, name)
        val target = existing ?: createDocument(tree, name)
        val bytes = BackupEncoding.compress(contents)
        // "rwt" truncates. Opening an existing document for writing without it would leave the
        // tail of a longer previous snapshot in place and leave the file as invalid gzip.
        resolver.openOutputStream(target, TRUNCATE)
            ?.use { it.write(bytes) }
            ?: throw IOException("Could not open $name in the chosen folder for writing")
    }

    override fun read(document: Uri): String {
        val bytes = resolver.openInputStream(document)
            ?.use { it.readBytes() }
            ?: throw IOException("Could not open the chosen backup for reading")
        return BackupEncoding.decode(bytes)
    }

    override fun persist(tree: Uri) {
        resolver.takePersistableUriPermission(
            tree,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    override fun release(tree: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /**
     * The document called [name] in [tree], or null if there is not one yet.
     *
     * Looked up rather than kept in preferences because a tree is a live view of somebody
     * else's folder: they can delete the file, and the next upload has to put it back rather
     * than write to a document that is no longer there.
     */
    private fun findDocument(tree: Uri, name: String): Uri? {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)

        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameColumn >= 0 && cursor.getString(nameColumn) == name && idColumn >= 0) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                }
            }
        }
        return null
    }

    private fun createDocument(tree: Uri, name: String): Uri {
        // The parent has to be a document URI, not the tree URI itself, or a provider that
        // checks will refuse to create anything inside it.
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        // Taken from the extension rather than fixed, because the app writes gzip and a
        // document labelled application/json that does not decompress as JSON misleads
        // whatever the user or Drive does with it next.
        val mime = if (name.endsWith(GZIP_EXTENSION)) GZIP_MIME else JSON_MIME
        return DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("Could not create $name in the chosen folder")
    }

    private companion object {
        const val JSON_MIME = "application/json"
        const val GZIP_MIME = "application/gzip"
        const val GZIP_EXTENSION = ".gz"
        const val TRUNCATE = "rwt"
    }
}

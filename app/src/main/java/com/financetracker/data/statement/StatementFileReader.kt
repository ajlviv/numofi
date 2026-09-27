package com.financetracker.data.statement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.financetracker.data.statement.pdf.AndroidPdfCharSource
import com.financetracker.data.statement.pdf.UkrsibbankStatementGrid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Chooses a reader for a picked file and returns its raw table. */
@Singleton
class StatementFileReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    sealed interface Result {
        data class Ok(val table: List<List<String>>) : Result
        data class Failed(val failure: StatementImportFailure) : Result
    }

    fun read(uri: Uri): Result {
        val fileName = displayName(uri)
        val extension = fileName.substringAfterLast('.', "").lowercase()

        if (extension == "xls") {
            // Legacy binary format; the XML-based reader cannot open it.
            return Result.Failed(StatementImportFailure.UNSUPPORTED_FORMAT)
        }

        if (extension == "pdf") {
            return openStream(uri) { stream ->
                Result.Ok(UkrsibbankStatementGrid.build(AndroidPdfCharSource(stream)))
            }
        }

        val reader: (InputStream) -> List<List<String>> = when (extension) {
            "xlsx", "xlsm" -> XlsxTableReader::read
            else -> CsvTableReader::read
        }

        return openStream(uri) { stream -> Result.Ok(reader(stream)) }
    }

    /** Runs [block] with the picked file's stream, mapping failures onto import errors. */
    private inline fun openStream(uri: Uri, block: (InputStream) -> Result): Result {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result.Failed(StatementImportFailure.UNREADABLE_FILE)
            stream.use(block)
        } catch (_: StatementTooLargeException) {
            Result.Failed(StatementImportFailure.TOO_LARGE)
        } catch (_: Throwable) {
            // Deliberately Throwable, not Exception: a hostile or merely odd file can
            // fail inside the PDF engine as an Error (a missing glyph table surfaces as
            // ExceptionInInitializerError, a LinkageError). Importing a statement is not
            // worth crashing the app over, so every failure becomes a reported error.
            Result.Failed(StatementImportFailure.UNREADABLE_FILE)
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}

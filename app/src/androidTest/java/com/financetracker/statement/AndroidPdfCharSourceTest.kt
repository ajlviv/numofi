package com.financetracker.statement

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financetracker.data.statement.pdf.AndroidPdfCharSource
import com.financetracker.data.statement.pdf.UkrsibbankStatementGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the PDF reader on a real device against a real statement.
 *
 * The JVM tests cover the parsing logic with desktop PDFBox, but they cannot cover the
 * Android PDFBox port, which loads its glyph tables from APK assets and fails with
 * ExceptionInInitializerError if [com.financetracker.FinanceTrackerApp] has not
 * initialised the resource loader. Only this test exercises that path.
 *
 * The sample is real personal financial data and is not committed. To run it, push the
 * statement into the app's external files directory first:
 *
 * adb shell mkdir -p /sdcard/Android/data/com.financetracker/files
 * adb push statement.pdf /sdcard/Android/data/com.financetracker/files/statement.pdf
 */
@RunWith(AndroidJUnit4::class)
class AndroidPdfCharSourceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun statement(): File {
        val external = File(context.getExternalFilesDir(null), "statement.pdf")
        if (external.exists()) return external
        return File(context.filesDir, "statement.pdf")
    }

    private fun grid(): List<List<String>> {
        val file = statement()
        assumeTrue("no statement pushed to the app's files directory", file.exists())

        val table = file.inputStream().use { stream ->
            UkrsibbankStatementGrid.build(AndroidPdfCharSource(stream))
        }
        assumeTrue("statement produced no table", table.isNotEmpty())
        return table
    }

    @Test
    fun readsARealStatementOnDevice() {
        val rows = grid()
        val data = rows.drop(1)

        assertTrue("expected the account operations and card tables", data.size >= 20)
        assertTrue(
            "Cyrillic descriptions did not survive extraction",
            data.any { it[1].contains("Поповнення") || it[1].contains("Оплата") }
        )

        // The foreign-currency purchase: the account-currency column, not USD 142.80.
        val foreign = data.firstOrNull { it[2] == "-6378.88" }
        assertTrue("foreign purchase missing from ${data.size} rows", foreign != null)
        assertEquals("UAH", foreign!![3])

        // An ordinary same-currency card payment.
        val cardPayment = data.first { it[2] == "-7500.00" }
        assertEquals("UAH", cardPayment[3])

        // Nothing that is not a settled movement may appear.
        val text = rows.joinToString(" ") { it.joinToString(" ") }
        assertTrue("totals leaked in", !text.contains("Разом"))
        assertTrue("pending holds leaked in", !text.contains("блокування"))
        assertTrue("card banner leaked in", !text.contains("MASTERCARD PLATINUM"))
    }
}

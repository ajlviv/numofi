package com.financetracker.statement

import com.financetracker.data.statement.StatementParseOutcome
import com.financetracker.data.statement.StatementParser
import com.financetracker.data.statement.pdf.PdfChar
import com.financetracker.data.statement.pdf.PdfCharSource
import com.financetracker.data.statement.pdf.UkrsibbankStatementGrid
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter

/**
 * Exercises the PDF statement parser against a real exported statement.
 *
 * The sample is a genuine bank document containing personal data, so it is deliberately
 * not committed as a test fixture and the tests are skipped when it is absent. Point
 * [sample] at a statement export to run them.
 */
class UkrsibbankStatementGridTest {

    private val sample: File? = sequenceOf(
        File("../$SAMPLE_NAME"),
        File(System.getProperty("user.home"), "Downloads/$SAMPLE_NAME")
    ).firstOrNull(File::isFile)

    /** Desktop PDFBox standing in for the Android port, which cannot load off-device. */
    private fun desktopSource(file: File): PdfCharSource = object : PdfCharSource {
        override fun read(): List<PdfChar> {
            val chars = mutableListOf<PdfChar>()
            var page = 0
            PDDocument.load(file).use { document ->
                val stripper = object : PDFTextStripper() {
                    override fun startPage(started: PDPage) {
                        page += 1
                        super.startPage(started)
                    }

                    override fun processTextPosition(text: TextPosition) {
                        val unicode = text.unicode
                        if (unicode.isNotEmpty() && !unicode[0].isISOControl()) {
                            chars += PdfChar(page, text.xDirAdj, text.yDirAdj, text.widthDirAdj, unicode[0])
                        }
                    }
                }
                stripper.writeText(document, StringWriter())
            }
            return chars
        }
    }

    private fun grid(): List<List<String>> {
        val file = sample
        assumeTrue("sample statement not present", file != null)
        val table = UkrsibbankStatementGrid.build(desktopSource(file!!))
        assumeTrue("statement produced no table", table.isNotEmpty())
        return table
    }

    @Test
    fun `reads the account operations table`() {
        val rows = grid()

        // 04.09.2026 top-up of 39 154.50, wrapped over a rule and two continuation lines.
        val topUp = rows.first { it[0] == "04.09.2026" && it[2] == "39154.50" }
        assertEquals("UAH", topUp[3])
        val description = topUp[1]
        assertTrue(
            "wrapped description was not joined, got: $description",
            description.contains("Поповнення рахунку") && description.contains("перерахуванням")
        )
    }

    @Test
    fun `keeps the account currency amount for a foreign purchase`() {
        val rows = grid()

        // USD 142.80 purchase whose account-currency column is UAH -6 378.88. Storing the
        // operation amount here instead would understate the row by roughly 45x.
        val foreign = rows.first { it[2] == "-6378.88" }
        assertEquals("UAH", foreign[3])
        assertEquals("09.09.2026", foreign[0])
        assertTrue("merchant missing, got: ${foreign[1]}", foreign[1].contains("JetBrains"))

        // The matching refund is the same purchase returned, in the same currency.
        val refund = rows.first { it[2] == "6326.04" }
        assertEquals("UAH", refund[3])
        assertEquals("11.09.2026", refund[0])
    }

    @Test
    fun `keeps card operations with their authorisation codes stripped`() {
        val rows = grid()

        val cardPayment = rows.first { it[2] == "-7500.00" }
        assertEquals("08.09.2026", cardPayment[0])
        val description = cardPayment[1]
        assertTrue("merchant missing, got: $description", description.contains("StomatologijaD"))
        assertTrue(
            "the six-digit authorisation code leaked into the description: $description",
            !description.contains("336719")
        )
    }

    @Test
    fun `signs debits and credits from the statement`() {
        val rows = grid()

        // The 39 154.50 credit is a salary top-up whose purpose is printed on the lines
        // below the row, so this also proves wrapped descriptions are joined.
        val topUp = rows.first { it[2] == "39154.50" }
        assertEquals("UAH", topUp[3])
        assertTrue(
            "expected the salary purpose, got: ${topUp[1]}",
            topUp[1].contains("Виплата зарплати") && topUp[1].contains("2026")
        )

        val transfer = rows.first { it[2] == "-20000.00" }
        assertEquals("UAH", transfer[3])
        assertTrue(
            "expected the outgoing transfer, got: ${transfer[1]}",
            transfer[1].contains("перерахування") && transfer[1].contains("інтернет-банкінг")
        )

        val commission = rows.first { it[2] == "-2.00" }
        assertTrue("expected a fee, got: ${commission[1]}", commission[1].contains("Комісія"))
    }

    @Test
    fun `excludes pending holds totals and card banners`() {
        val rows = grid()
        val data = rows.drop(1)
        val text = rows.joinToString(" ") { it.joinToString(" ") }

        assertTrue("totals must not become transactions", !text.contains("Разом"))
        assertTrue("card banner must not become a transaction", !text.contains("MASTERCARD PLATINUM"))
        assertTrue("separator rules must not leak in", !text.contains("_ _ _"))
        assertTrue("every row must carry a statement date", data.all { it[0].matches(DATE) })
    }

    @Test
    fun `produces a table the shared statement parser accepts`() {
        val rows = grid()

        val outcome = StatementParser.parse(rows)
        assertTrue("parser rejected the generated table: $outcome", outcome is StatementParseOutcome.Parsed)

        val parsed = (outcome as StatementParseOutcome.Parsed).result
        // Every generated row must survive; none silently dropped by the shared parser.
        assertEquals(rows.size - 1, parsed.rows.size)
        assertEquals(0, parsed.skippedRows)
        assertTrue("expected debits", parsed.rows.any { it.isExpense })
        assertTrue("expected credits", parsed.rows.any { !it.isExpense })
        // The ledger stays in the account currency, so nothing is left unlabelled.
        assertTrue("unlabelled currency", parsed.rows.all { it.currencyCode == "UAH" })
    }

    @Test
    fun `rows are labelled with the card banner above their section`() {
        val data = grid().drop(1)
        val labelled = data.filter { it.getOrNull(4)?.isNotBlank() == true }

        assertTrue(
            "expected at least one card-labelled row, got ${data.size} rows",
            labelled.isNotEmpty()
        )
        assertTrue(
            "unexpected labels: ${labelled.map { it[4] }.distinct()}",
            labelled.all { it[4] == "535129****5783" }
        )
    }

    @Test
    fun `a card payment carries its card label, an account movement does not`() {
        val data = grid().drop(1)

        // 08.09.2026 is in the card table, under the MASTERCARD banner.
        val cardPayment = data.first { it[2] == "-7500.00" }
        assertEquals("535129****5783", cardPayment.getOrNull(4))

        // The salary top-up is account-level, so it has no card to be attributed to.
        val topUp = data.first { it[2] == "39154.50" }
        assertTrue(
            "an account movement must not inherit the card label, got '${topUp.getOrNull(4)}'",
            topUp.getOrNull(4).isNullOrBlank()
        )
    }

    @Test
    fun `the parser reads the card column back off the generated table`() {
        val outcome = StatementParser.parse(grid()) as StatementParseOutcome.Parsed
        val labelled = outcome.result.rows.filter { it.cardLabel != null }
        assertTrue("the shared parser saw no card labels", labelled.isNotEmpty())
    }

    private companion object {
        const val SAMPLE_NAME = "9e4ea49c-e1e9-4a6e-9988-a0059750b0bc.pdf"
        val DATE = Regex("""^\d{2}\.\d{2}\.\d{4}$""")
    }
}

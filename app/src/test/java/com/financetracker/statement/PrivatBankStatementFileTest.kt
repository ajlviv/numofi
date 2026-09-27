package com.financetracker.statement

import com.financetracker.data.statement.BankDetector
import com.financetracker.data.statement.StatementParseOutcome
import com.financetracker.data.statement.StatementParser
import com.financetracker.data.statement.XlsxTableReader
import com.financetracker.model.BankCode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the spreadsheet reader and the bank detector against a real exported statement.
 *
 * The sample is a genuine bank document containing personal data, so it is deliberately not
 * committed as a test fixture and the tests are skipped when it is absent. Point [sample] at
 * a statement export to run them.
 */
class PrivatBankStatementFileTest {

    private val sample: File? = sequenceOf(
        File("../$SAMPLE_NAME"),
        File(System.getProperty("user.home"), "Downloads/$SAMPLE_NAME")
    ).firstOrNull(File::isFile)

    private fun table(): List<List<String>> {
        val file = sample
        assumeTrue("sample statement not present", file != null)
        val rows = file!!.inputStream().use { XlsxTableReader.read(it) }
        assumeTrue("statement produced no table", rows.isNotEmpty())
        return rows
    }

    private fun parsed() =
        StatementParser.parse(table()) as? StatementParseOutcome.Parsed
            ?: error("the shared parser rejected the real statement")

    @Test
    fun `is attributed to PrivatBank`() {
        assertEquals(BankCode.PRIVATBANK, BankDetector.detect(table()))
    }

    @Test
    fun `carries its header on the second row, under the statement title`() {
        val rows = table()
        assertEquals("Дата", rows[1].firstOrNull { it.isNotBlank() })
    }

    @Test
    fun `every transaction survives the shared parser`() {
        val result = parsed().result
        assertEquals(0, result.skippedRows)
        assertEquals(81, result.rows.size)
        assertTrue("expected debits", result.rows.any { it.isExpense })
        assertTrue("expected credits", result.rows.any { !it.isExpense })
    }

    @Test
    fun `every transaction carries a card label and a currency`() {
        val rows = parsed().result.rows
        val unlabelled = rows.count { it.cardLabel.isNullOrBlank() }
        assertEquals("rows without a card label: $unlabelled", 0, unlabelled)
        assertEquals(setOf("UAH"), rows.map { it.currencyCode }.toSet())
    }

    @Test
    fun `the statement covers two cards`() {
        val labels = parsed().result.rows.mapNotNull { it.cardLabel }.toSet()
        assertEquals("distinct card labels: $labels", 2, labels.size)
    }

    private companion object {
        const val SAMPLE_NAME = "3zX4ILkRQPG0q8jpWBV7btV9qNq0fw5PbvPBlg7xJpE (1).xlsx"
    }
}

package com.financetracker.statement

import com.financetracker.data.statement.CsvTableReader
import com.financetracker.data.statement.StatementImportFailure
import com.financetracker.data.statement.StatementParseOutcome
import com.financetracker.data.statement.StatementParser
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementParserTest {

    private fun parse(vararg rows: String) = StatementParser.parse(
        CsvTableReader.read(ByteArrayInputStream(rows.joinToString("\n").toByteArray()))
    )

    private fun parsed(vararg rows: String) =
        (parse(*rows) as StatementParseOutcome.Parsed).result

    private fun failed(vararg rows: String) =
        (parse(*rows) as StatementParseOutcome.Failed).failure

    private fun Long.asDate() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    @Test
    fun `finds the header row below a title block`() {
        val result = parsed(
            "Виписка по рахунку",
            "Період: 01.09.2026 - 27.09.2026",
            "Дата;Опис;Сума;Валюта;Баланс",
            "01.09.2026;Кав'ярня;−95,50;UAH;950,50"
        )

        assertEquals(1, result.rows.size)
        assertEquals(LocalDate.of(2026, 9, 1), result.rows[0].timestamp.asDate())
        assertEquals(BigDecimal("95.50"), result.rows[0].absoluteAmount)
        assertEquals("UAH", result.rows[0].currencyCode)
    }

    @Test
    fun `negative amounts become expenses`() {
        val result = parsed(
            "Date;Description;Amount",
            "02.09.2026;Coffee;-95.50",
            "03.09.2026;Salary;25000.00"
        )

        assertTrue(result.rows[0].isExpense)
        assertEquals(BigDecimal("95.50"), result.rows[0].amount.abs())
        assertEquals(false, result.rows[1].isExpense)
        assertEquals(BigDecimal("25000.00"), result.rows[1].amount)
    }

    @Test
    fun `parses comma decimals and thousands grouping`() {
        assertEquals(BigDecimal("1234.56"), StatementParser.parseDecimal("1 234,56"))
        assertEquals(BigDecimal("1234.56"), StatementParser.parseDecimal("1,234.56"))
        assertEquals(BigDecimal("1234"), StatementParser.parseDecimal("1 234"))
        assertEquals(BigDecimal("-42.50"), StatementParser.parseDecimal("(42.50)"))
        assertEquals(BigDecimal("100.50"), StatementParser.parseDecimal("₴100,50"))
    }

    @Test
    fun `keeps a signed amount column ahead of a debit credit pair`() {
        val result = parsed(
            "Дата;Сума;Дебет;Кредит",
            "01.09.2026;-10,00;;",
            "01.09.2026;20,00;;5,00"
        )

        assertEquals(BigDecimal("-10.00"), result.rows[0].amount)
        assertEquals(BigDecimal("20.00"), result.rows[1].amount)
    }

    @Test
    fun `nets debit and credit when there is no signed amount column`() {
        val result = parsed(
            "Дата;Дебет;Кредит",
            "01.09.2026;10,00;;",
            "02.09.2026;;25,00"
        )

        assertEquals(BigDecimal("-10.00"), result.rows[0].amount)
        assertEquals(BigDecimal("25.00"), result.rows[1].amount)
    }

    @Test
    fun `ignores commission and tax columns`() {
        val result = parsed(
            "Дата;Опис;Сума;Комісія;Податок",
            "01.09.2026;Переказ;-100,00;-1,00;-0,50"
        )

        assertEquals(BigDecimal("-100.00"), result.rows[0].amount)
    }

    @Test
    fun `divides amounts when the header says kopecks`() {
        val result = parsed(
            "Дата;Сума, коп",
            "01.09.2026;-9550"
        )

        assertTrue(result.columns.amountsAreMinorUnits)
        assertEquals(BigDecimal("-95.50"), result.rows[0].amount)
    }

    @Test
    fun `reads an excel serial date and several text layouts`() {
        assertEquals(
            LocalDate.of(2026, 9, 1),
            StatementParser.parseDate("46266")!!.asDate()
        )
        listOf("01.09.2026", "01/09/2026", "2026-09-01", "2026-09-01 14:30:00")
            .forEach { assertEquals(LocalDate.of(2026, 9, 1), StatementParser.parseDate(it)!!.asDate()) }
    }

    @Test
    fun `counts rows it could not use`() {
        val result = parsed(
            "Дата;Опис;Сума",
            "01.09.2026;Ok;-10,00",
            "не дата;Bad;;",
            "02.09.2026;Ok;-20,00"
        )

        assertEquals(2, result.rows.size)
        assertEquals(1, result.skippedRows)
    }

    @Test
    fun `reports a missing amount column`() {
        assertEquals(
            StatementImportFailure.NO_AMOUNT_COLUMN,
            failed("Дата;Опис", "01.09.2026;Кав'ярня")
        )
    }

    @Test
    fun `reports a missing date column`() {
        assertEquals(
            StatementImportFailure.NO_DATE_COLUMN,
            failed("Опис;Сума", "Кав'ярня;-10,00")
        )
    }

    @Test
    fun `reports an empty file`() {
        assertEquals(StatementImportFailure.EMPTY_FILE, failed(""))
    }
}

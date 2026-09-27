package com.financetracker.statement

import com.financetracker.data.statement.StatementParseOutcome
import com.financetracker.data.statement.StatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardColumnTest {

    private fun parse() = StatementParser.parse(
        listOf(
            listOf("Дата операції", "Опис операції", "Сума в валюті рахунку", "Валюта", "Картка"),
            listOf("01.09.2026", "TORUS", "-100.00", "UAH", "535129****5783"),
            listOf("02.09.2026", "Поповнення", "50.00", "UAH", "")
        )
    ) as StatementParseOutcome.Parsed

    @Test
    fun `the card column is read into the row`() {
        assertEquals("535129****5783", parse().result.rows.first().cardLabel)
    }

    @Test
    fun `a blank card cell leaves the row unattributed rather than empty`() {
        assertNull(parse().result.rows[1].cardLabel)
    }

    @Test
    fun `a card cell is trimmed`() {
        val outcome = StatementParser.parse(
            listOf(
                listOf("Дата операції", "Опис операції", "Сума", "Валюта", "Картка"),
                listOf("01.09.2026", "TORUS", "-100.00", "UAH", "  535129****5783  ")
            )
        ) as StatementParseOutcome.Parsed
        assertEquals("535129****5783", outcome.result.rows.single().cardLabel)
    }

    @Test
    fun `a statement without a card column still parses`() {
        val outcome = StatementParser.parse(
            listOf(
                listOf("Дата операції", "Опис операції", "Сума", "Валюта"),
                listOf("01.09.2026", "TORUS", "-100.00", "UAH")
            )
        ) as StatementParseOutcome.Parsed
        assertNull(outcome.result.rows.single().cardLabel)
    }

    @Test
    fun `an existing row index and amounts are unaffected by the extra column`() {
        val result = parse().result
        assertEquals(2, result.rows.size)
        assertEquals("01.09.2026", "01.09.2026")
        assertEquals("UAH", result.rows.first().currencyCode)
    }
}

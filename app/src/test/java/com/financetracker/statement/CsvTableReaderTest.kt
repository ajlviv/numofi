package com.financetracker.statement

import com.financetracker.data.statement.CsvTableReader
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTableReaderTest {

    private fun read(content: String) =
        CsvTableReader.read(ByteArrayInputStream(content.toByteArray()))

    @Test
    fun `sniffs semicolons over commas in a decimal locale`() {
        val rows = read("Дата;Сума\n01.09.2026;-1234,56")

        assertEquals(listOf(listOf("Дата", "Сума"), listOf("01.09.2026", "-1234,56")), rows)
    }

    @Test
    fun `honours quoting around embedded separators and newlines`() {
        val rows = read("a,b\n\"x;1\",\"line1\nline2\"")

        assertEquals(listOf("x;1", "line1\nline2"), rows[1])
    }

    @Test
    fun `unescapes doubled quotes`() {
        val rows = read("a\n\"say \"\"hi\"\"\"")

        assertEquals(listOf("say \"hi\""), rows[1])
    }

    @Test
    fun `strips a utf8 byte order mark`() {
        val rows = read("\uFEFFДата;Сума")

        assertEquals(listOf("Дата", "Сума"), rows[0])
    }

    @Test
    fun `handles tab separated files`() {
        val rows = read("Date\tAmount\n2026-09-01\t-10.00")

        assertEquals(listOf("Date", "Amount"), rows[0])
        assertEquals(listOf("2026-09-01", "-10.00"), rows[1])
    }

    @Test
    fun `ignores carriage returns and trailing blank lines`() {
        val rows = read("a,b\r\n1,2\r\n\r\n")

        assertEquals(2, rows.size)
        assertEquals(listOf("1", "2"), rows[1])
    }
}

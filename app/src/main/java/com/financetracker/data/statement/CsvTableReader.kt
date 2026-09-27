package com.financetracker.data.statement

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Minimal RFC 4180 CSV reader.
 *
 * Bank exports vary in separator, encoding and quoting, so this sniffs the delimiter
 * from the header and tolerates a UTF-8 BOM, quoted separators and embedded newlines.
 */
object CsvTableReader {

    private val DELIMITERS = charArrayOf(';', ',', '\t', '|')

    fun read(input: InputStream): List<List<String>> {
        val text = readText(input).removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()

        val delimiter = sniffDelimiter(text)
        return parse(text, delimiter)
    }

    private fun readText(input: InputStream): String =
        InputStreamReader(input, StandardCharsets.UTF_8).use { it.readText() }

    /**
     * Picks the candidate separator that yields the most fields in the first
     * non-empty line, so a description containing a comma does not win over ';'.
     */
    private fun sniffDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        return DELIMITERS.maxByOrNull { countOutsideQuotes(firstLine, it) } ?: ','
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var count = 0
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> count++
            }
        }
        return count
    }

    private fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            row.add(field.toString().trim())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            // Skip rows that are entirely empty, which trailing newlines produce.
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    // Escaped quote inside a quoted field.
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                inQuotes -> field.append(c)
                c == delimiter -> endField()
                c == '\r' -> Unit
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows
    }
}

package com.financetracker.data.statement

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Minimal XLSX reader built on `java.util.zip` and `javax.xml.parsers`, both of which
 * are available on Android and the JVM.
 *
 * Avoids Apache POI, which adds several megabytes and needs desugaring. It covers what
 * a statement export actually uses: the first worksheet, shared strings, inline
 * strings, numbers, and sparse/blank cells. Formula cells are read from their cached
 * `<v>` value, which is what Excel last computed.
 */
object XlsxTableReader {

    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val WORKBOOK = "xl/workbook.xml"
    private const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"

    /** Statement exports are small; anything larger is not one. Guards zip bombs. */
    private const val MAX_UNCOMPRESSED_BYTES = 20L * 1024 * 1024

    fun read(input: InputStream): List<List<String>> {
        val entries = readEntries(input)
        if (entries.isEmpty()) return emptyList()

        val sharedStrings = entries[SHARED_STRINGS]?.let(::parseSharedStrings).orEmpty()
        val sheet = entries[resolveFirstSheet(entries)] ?: return emptyList()
        return parseSheet(sheet, sharedStrings)
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    totalBytes += bytes.size
                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw StatementTooLargeException()
                    }
                    entries[entry.name] = bytes
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    /**
     * Resolves the first worksheet in workbook order so the user sees the sheet they
     * expect, falling back to the conventional `sheet1.xml`.
     */
    private fun resolveFirstSheet(entries: Map<String, ByteArray>): String {
        val workbook = entries[WORKBOOK]
        val rels = entries[WORKBOOK_RELS]
        if (workbook != null && rels != null) {
            val target = parseFirstSheetRid(workbook)?.let { rid -> parseWorksheetTargets(rels)[rid] }
            val normalized = target?.let {
                if (it.startsWith("/")) it.removePrefix("/") else "xl/$it"
            }
            if (normalized != null && entries.containsKey(normalized)) return normalized
        }
        return entries.keys.firstOrNull { it.startsWith("xl/worksheets/") }
            ?: "xl/worksheets/sheet1.xml"
    }

    private fun parseFirstSheetRid(xml: ByteArray): String? {
        var rid: String? = null
        walk(xml) { name, attrs, _, atEnd ->
            if (!atEnd && name == "sheet" && rid == null) rid = attrs["r:id"]
        }
        return rid
    }

    private fun parseWorksheetTargets(xml: ByteArray): Map<String, String> {
        val targets = mutableMapOf<String, String>()
        walk(xml) { name, attrs, _, atEnd ->
            if (atEnd) return@walk
            val id = attrs["Id"]
            val target = attrs["Target"]
            if (name == "Relationship" && id != null && target != null &&
                attrs["Type"]?.endsWith("/worksheet") == true
            ) {
                targets[id] = target
            }
        }
        return targets
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        var inItem = false
        var inText = false
        val buffer = StringBuilder()

        walk(xml) { name, _, text, atEnd ->
            when {
                !atEnd && name == "si" -> {
                    inItem = true
                    inText = false
                    buffer.setLength(0)
                }
                // A shared string can be split across rich-text runs (<r><t>..</t></r>).
                !atEnd && name == "t" -> inText = inItem
                atEnd && name == "si" -> if (inItem) {
                    strings.add(buffer.toString())
                    inItem = false
                    inText = false
                }
                else -> if (inText) buffer.append(text)
            }
        }
        return strings
    }

    private fun parseSheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var cellColumn = 0
        var cellType = ""
        var cellValue = StringBuilder()
        var capturing = Capture.NONE

        fun closeCell() {
            val value = resolveCell(cellType, cellValue.toString(), sharedStrings)
            while (row.size <= cellColumn) row.add("")
            row[cellColumn] = value
            cellValue = StringBuilder()
            capturing = Capture.NONE
        }

        walk(xml) { name, attrs, text, atEnd ->
            when {
                atEnd && name == "c" -> closeCell()
                atEnd && name == "row" -> {
                    rows.add(row)
                    row = mutableListOf()
                }
                !atEnd && name == "row" -> {
                    // Honour the row index so skipped rows keep later indices aligned.
                    val declared = attrs["r"]?.toIntOrNull()
                    if (declared != null) {
                        while (rows.size < declared - 1) rows.add(emptyList())
                    }
                    row = mutableListOf()
                }
                !atEnd && name == "c" -> {
                    cellColumn = attrs["r"]?.let(::columnIndexOf) ?: row.size
                    cellType = attrs["t"].orEmpty()
                    cellValue = StringBuilder()
                    capturing = Capture.NONE
                }
                // Reset before capturing so any whitespace between <c> and <v> is dropped.
                !atEnd && name == "v" -> {
                    cellValue = StringBuilder()
                    capturing = Capture.VALUE
                }
                !atEnd && name == "is" -> {
                    cellValue = StringBuilder()
                    capturing = Capture.INLINE
                }
                // <f> holds the formula text, not the result; ignore it.
                !atEnd && name == "f" -> capturing = Capture.NONE
                else -> if (capturing != Capture.NONE) cellValue.append(text)
            }
        }
        return rows
    }

    private fun resolveCell(type: String, raw: String, sharedStrings: List<String>): String {
        val value = raw.trim()
        return when (type) {
            "s" -> value.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
            "inlineStr", "str" -> value
            "b" -> if (value == "1") "TRUE" else "FALSE"
            else -> value
        }
    }

    private enum class Capture { NONE, VALUE, INLINE }

    /** `B7` -> 1. Returns 0 for references without a column part. */
    private fun columnIndexOf(reference: String): Int {
        var index = 0
        var seen = false
        for (c in reference) {
            if (!c.isLetter()) break
            index = index * 26 + (c.uppercaseChar() - 'A' + 1)
            seen = true
        }
        return if (seen) (index - 1).coerceAtLeast(0) else 0
    }

    private fun walk(
        xml: ByteArray,
        onElement: (name: String, attrs: Map<String, String>, text: String, atEnd: Boolean) -> Unit
    ) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        val handler = object : DefaultHandler() {
            private val buffer = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes?) {
                buffer.setLength(0)
                onElement(qName ?: localName.orEmpty(), attrs?.toMap().orEmpty(), "", false)
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                buffer.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                onElement(qName ?: localName.orEmpty(), emptyMap(), buffer.toString(), true)
                buffer.setLength(0)
            }
        }
        factory.newSAXParser().parse(xml.inputStream(), handler)
    }

    private fun Attributes.toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until length) {
            val name = getQName(i)
            if (name != null) map[name] = getValue(i).orEmpty()
        }
        return map
    }
}

class StatementTooLargeException : Exception("Statement file is too large")

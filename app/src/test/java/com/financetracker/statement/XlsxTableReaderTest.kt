package com.financetracker.statement

import com.financetracker.data.statement.XlsxTableReader
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class XlsxTableReaderTest {

    /**
     * Builds a minimal but valid workbook so the reader is exercised against the real
     * zip-plus-XML layout rather than a mock.
     */
    private fun buildXlsx(sheet: String, sharedStrings: List<String> = emptyList()): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put(
                "xl/workbook.xml",
                """<workbook><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<Relationships><Relationship Id="rId1" """ +
                    """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
                    """Target="worksheets/sheet1.xml"/></Relationships>"""
            )
            if (sharedStrings.isNotEmpty()) {
                val items = sharedStrings.joinToString("") { "<si><t>${it.escapeXml()}</t></si>" }
                put("xl/sharedStrings.xml", """<sst count="${sharedStrings.size}">$items</sst>""")
            }
            put("xl/worksheets/sheet1.xml", sheet)
        }
        return out.toByteArray()
    }

    private fun String.escapeXml() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun read(bytes: ByteArray) = XlsxTableReader.read(ByteArrayInputStream(bytes))

    @Test
    fun `reads shared strings, inline strings and numbers`() {
        val bytes = buildXlsx(
            sheet = """
                <worksheet><sheetData>
                  <row r="1">
                    <c r="A1" t="s"><v>0</v></c>
                    <c r="B1" t="s"><v>1</v></c>
                    <c r="C1" t="s"><v>2</v></c>
                  </row>
                  <row r="2">
                    <c r="A2" t="inlineStr"><is><t>01.09.2026</t></is></c>
                    <c r="B2" t="s"><v>3</v></c>
                    <c r="C2"><v>-95.5</v></c>
                  </row>
                </sheetData></worksheet>
            """.trimIndent(),
            sharedStrings = listOf("Дата", "Опис", "Сума", "Кав'ярня")
        )

        val rows = read(bytes)

        assertEquals(listOf("Дата", "Опис", "Сума"), rows[0])
        assertEquals(listOf("01.09.2026", "Кав'ярня", "-95.5"), rows[1])
    }

    @Test
    fun `keeps blank cells so column indices line up`() {
        val bytes = buildXlsx(
            sheet = """
                <worksheet><sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>Date</t></is></c>
                    <c r="B1" t="inlineStr"><is><t>Amount</t></is></c></row>
                  <row r="2"><c r="A2" t="inlineStr"><is><t>2026-09-01</t></is></c>
                    <c r="C2"><v>7</v></c></row>
                </sheetData></worksheet>
            """.trimIndent()
        )

        val rows = read(bytes)

        assertEquals(listOf("2026-09-01", "", "7"), rows[1])
    }

    @Test
    fun `reads a formula from its cached value`() {
        val bytes = buildXlsx(
            sheet = """
                <worksheet><sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>Amount</t></is></c></row>
                  <row r="2"><c r="A2"><f>SUM(B1:B9)</f><v>1234.56</v></c></row>
                </sheetData></worksheet>
            """.trimIndent()
        )

        assertEquals(listOf("1234.56"), read(bytes)[1])
    }

    @Test
    fun `joins rich text runs in shared strings`() {
        val bytes = buildXlsx(
            sheet = """
                <worksheet><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                </sheetData></worksheet>
            """.trimIndent(),
            sharedStrings = emptyList()
        ).let {
            // Overwrite the shared strings part with a rich-text entry.
            val out = java.io.ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                fun put(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                put("xl/sharedStrings.xml", """<sst><si><r><t>Призначе</t></r><r><t>ння</t></r></si></sst>""")
                put(
                    "xl/workbook.xml",
                    """<workbook><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>"""
                )
                put(
                    "xl/_rels/workbook.xml.rels",
                    """<Relationships><Relationship Id="rId1" """ +
                        """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
                        """Target="worksheets/sheet1.xml"/></Relationships>"""
                )
                put(
                    "xl/worksheets/sheet1.xml",
                    """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>"""
                )
            }
            out.toByteArray()
        }

        assertEquals(listOf("Призначення"), read(bytes)[0])
    }

    @Test
    fun `returns nothing for a file that is not a zip`() {
        assertEquals(emptyList<List<String>>(), read("not a zip".toByteArray()))
    }
}

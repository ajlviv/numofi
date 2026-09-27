package com.financetracker.data.statement.pdf

import com.financetracker.data.statement.StatementTooLargeException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream
import java.io.StringWriter

/**
 * Reads positioned characters on device using the Android PDFBox port.
 *
 * [PDDocument.load] is lazy, so the stream is only read as far as the text stripper
 * walks it; the caller owns and closes the stream.
 */
class AndroidPdfCharSource(private val stream: InputStream) : PdfCharSource {

    override fun read(): List<PdfChar> {
        val chars = mutableListOf<PdfChar>()
        var page = 0

        PDDocument.load(stream).use { document ->
            val stripper = object : PDFTextStripper() {
                // PDFBox 2.x has no page number on TextPosition, so pages are counted as
                // the stripper walks them. Each page has its own coordinate space.
                override fun startPage(started: PDPage) {
                    page += 1
                    super.startPage(started)
                }

                override fun processTextPosition(text: TextPosition) {
                    // A real statement is tens of thousands of characters. Refusing to
                    // accumulate without bound keeps a hostile or accidental huge file
                    // from exhausting the heap.
                    if (chars.size >= MAX_CHARS) throw StatementTooLargeException()

                    val unicode = text.unicode
                    if (unicode.isNotEmpty() && !unicode[0].isISOControl()) {
                        chars += PdfChar(
                            page = page,
                            x = text.xDirAdj,
                            y = text.yDirAdj,
                            width = text.widthDirAdj,
                            char = unicode[0]
                        )
                    }
                }
            }
            // Output is discarded; coordinates are collected by the override above.
            stripper.writeText(document, StringWriter())
        }

        return chars
    }

    private companion object {
        const val MAX_CHARS = 4_000_000
    }
}

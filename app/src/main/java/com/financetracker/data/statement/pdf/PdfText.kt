package com.financetracker.data.statement.pdf

/**
 * A single character and where it sits on the page.
 *
 * Deliberately free of any PDF library type: the PDF engines available to Android
 * (the `com.tom_roush` port) cannot be loaded on a desktop JVM, so unit tests drive
 * the parser through desktop PDFBox instead. Both engines implement [PdfCharSource].
 */
data class PdfChar(
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val char: Char
)

/** Characters that sit next to each other on one baseline with no meaningful gap. */
data class PdfRun(val x: Float, val endX: Float, val text: String)

/** Everything drawn on a single baseline, split into runs, ordered top of page first. */
data class PdfLine(val y: Float, val runs: List<PdfRun>) {
    val text: String get() = runs.joinToString(" ") { it.text }
}

/** Supplies the positioned characters of a document. */
interface PdfCharSource {
    fun read(): List<PdfChar>
}

/**
 * Rebuilds lines and runs from raw character positions.
 *
 * A PDF has no notion of a table cell, only glyphs at coordinates, so rows have to be
 * recovered from geometry: characters on the same baseline form a line, and a gap wider
 * than a space means a new run.
 */
object PositionedText {

    /** Anything wider than this is treated as a column break rather than a space. */
    private const val RUN_GAP = 1.2f

    /** Half-point buckets: statement baselines are far enough apart to survive rounding. */
    private fun baseline(y: Float): Float = Math.round(y * 2f) / 2f

    /**
     * Lines grouped per page, each page's lines ordered from the bottom of the page up.
     *
     * Grouping by page first is essential: every page restarts its own coordinate space,
     * so sorting all characters by y alone interleaves unrelated pages and welds
     * unrelated lines into single rows.
     */
    fun pages(chars: List<PdfChar>): List<List<PdfLine>> =
        chars.groupBy { it.page }
            .toSortedMap()
            .values
            .map { onPage ->
                onPage.groupBy { baseline(it.y) }
                    .map { (y, onLine) -> PdfLine(y, runs(onLine.sortedBy { it.x })) }
                    .sortedByDescending { it.y }
            }

    private fun runs(sorted: List<PdfChar>): List<PdfRun> {
        val result = mutableListOf<PdfRun>()
        var current = mutableListOf<PdfChar>()

        fun flush() {
            if (current.isEmpty()) return
            result += PdfRun(
                x = current.first().x,
                endX = current.last().x + current.last().width,
                text = current.joinToString("") { it.char.toString() }.trim()
            )
            current = mutableListOf()
        }

        for (char in sorted) {
            val previous = current.lastOrNull()
            if (previous != null && char.x - (previous.x + previous.width) > RUN_GAP) {
                flush()
            }
            current += char
        }
        flush()
        return result.filter { it.text.isNotEmpty() }
    }
}

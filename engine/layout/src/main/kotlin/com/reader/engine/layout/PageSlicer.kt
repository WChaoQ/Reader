package com.reader.engine.layout

import android.text.StaticLayout

/**
 * Turns a measured [StaticLayout] into page [PageSlice]s.
 *
 * Cut priority (architecture §7.2):
 * 1. Fill the content box as much as possible
 * 2. Prefer paragraph boundaries within a limited pull-back budget
 * 3. Avoid widows (last line of chapter alone on a page)
 * 4. Avoid orphans (last line of a paragraph alone at the top of a page)
 * 5. Keep short heading-like lines with the following body
 * 6. Never produce an empty page; always advance at least one line
 */
object PageSlicer {

    private const val MIN_LINES_PER_PAGE = 2
    /** Max fraction of page height we may leave empty to land on a paragraph break. */
    private const val MAX_PARAGRAPH_PULL_FRACTION = 0.28f
    /** Line width below this fraction of the layout width is treated as "heading-like". */
    private const val SHORT_LINE_FRACTION = 0.42f
    /** Keep a short line with at least this many following lines when possible. */
    private const val HEADING_KEEP_WITH_NEXT = 1

    fun slice(layout: StaticLayout, contentHeight: Int): List<PageSlice> {
        val lineCount = layout.lineCount
        if (lineCount <= 0 || layout.text.isEmpty()) {
            return listOf(PageSlice(0, 0, 0, 0))
        }

        val pages = mutableListOf<PageSlice>()
        var pendingLeadingBlank: PageSlice? = null
        var line = 0
        while (line < lineCount) {
            val top = layout.getLineTop(line)
            var endLine = maxEndLineByHeight(layout, line, top, contentHeight, lineCount)

            endLine = applyWidowControl(line, endLine, lineCount)
            endLine = avoidOrphanAtNextPage(layout, line, endLine, lineCount, top, contentHeight)
            endLine = preferParagraphBoundary(layout, line, endLine, top, contentHeight)
            endLine = keepHeadingWithBody(layout, line, endLine, lineCount, top, contentHeight)

            // Height safety net after heuristics that may have expanded the range.
            while (endLine > line && layout.getLineBottom(endLine) - top > contentHeight) {
                endLine--
            }

            val slice = PageSlice(
                startLine = line,
                endLine = endLine,
                startOffset = layout.getLineStart(line),
                endOffset = layout.getLineEnd(endLine),
            )
            val sliceText = layout.text.subSequence(slice.startOffset, slice.endOffset)
            if (sliceText.isBlank()) {
                if (pages.isNotEmpty()) {
                    // Blank-only line groups belong to the preceding page.
                    val previous = pages.removeAt(pages.lastIndex)
                    pages += previous.copy(
                        endLine = slice.endLine,
                        endOffset = slice.endOffset,
                    )
                } else {
                    val pending = pendingLeadingBlank
                    pendingLeadingBlank = if (pending == null) {
                        slice
                    } else {
                        pending.copy(endLine = slice.endLine, endOffset = slice.endOffset)
                    }
                }
            } else {
                val leadingBlank = pendingLeadingBlank
                pages += if (leadingBlank == null) {
                    slice
                } else {
                    slice.copy(
                        startLine = leadingBlank.startLine,
                        startOffset = leadingBlank.startOffset,
                    )
                }
                pendingLeadingBlank = null
            }
            line = endLine + 1
        }

        pendingLeadingBlank?.let { blank ->
            if (pages.isNotEmpty()) {
                val previous = pages.removeAt(pages.lastIndex)
                pages += previous.copy(endLine = blank.endLine, endOffset = blank.endOffset)
            } else {
                pages += blank
            }
        }

        return pages.ifEmpty {
            listOf(PageSlice(0, 0, 0, layout.text.length))
        }
    }

    /**
     * Returns true when [pages] are consistent with [layout] (same line/offset bounds).
     * Used to reject stale disk-cache entries without trusting them blindly.
     */
    fun isValid(layout: StaticLayout, pages: List<PageSlice>): Boolean {
        if (pages.isEmpty()) return false
        val lineCount = layout.lineCount
        val textLength = layout.text.length
        if (lineCount <= 0) {
            return pages.size == 1 &&
                pages[0].startLine == 0 && pages[0].endLine == 0 &&
                pages[0].startOffset == 0 && pages[0].endOffset == 0
        }

        var expectedStartLine = 0
        var expectedStartOffset = 0
        pages.forEach { slice ->
            if (slice.startLine != expectedStartLine) return false
            if (slice.endLine < slice.startLine || slice.endLine >= lineCount) return false
            if (slice.startOffset != expectedStartOffset) return false
            if (slice.startOffset != layout.getLineStart(slice.startLine)) return false
            if (slice.endOffset != layout.getLineEnd(slice.endLine)) return false
            if (slice.startOffset < 0 || slice.endOffset < slice.startOffset || slice.endOffset > textLength) return false
            if (pages.size > 1 && layout.text.subSequence(slice.startOffset, slice.endOffset).isBlank()) return false

            expectedStartLine = slice.endLine + 1
            expectedStartOffset = slice.endOffset
        }
        return expectedStartLine == lineCount && expectedStartOffset == textLength
    }

    private fun maxEndLineByHeight(
        layout: StaticLayout,
        startLine: Int,
        top: Int,
        contentHeight: Int,
        lineCount: Int,
    ): Int {
        var endLine = startLine
        while (endLine + 1 < lineCount &&
            layout.getLineBottom(endLine + 1) - top <= contentHeight
        ) {
            endLine++
        }
        return endLine
    }

    private fun applyWidowControl(startLine: Int, endLine: Int, lineCount: Int): Int {
        val remaining = lineCount - (endLine + 1)
        val pageLines = endLine - startLine + 1
        if (remaining == 1 && pageLines > MIN_LINES_PER_PAGE) {
            return endLine - 1
        }
        return endLine
    }

    private fun avoidOrphanAtNextPage(
        layout: StaticLayout,
        startLine: Int,
        endLine: Int,
        lineCount: Int,
        top: Int,
        contentHeight: Int,
    ): Int {
        if (endLine + 1 >= lineCount) return endLine
        val next = endLine + 1
        if (!isParagraphEnd(layout, next)) return endLine
        if (endLine >= startLine && isParagraphEnd(layout, endLine)) return endLine

        if (layout.getLineBottom(next) - top <= contentHeight) {
            return next
        }
        if (endLine - startLine + 1 > MIN_LINES_PER_PAGE) {
            return endLine - 1
        }
        return endLine
    }

    private fun preferParagraphBoundary(
        layout: StaticLayout,
        startLine: Int,
        endLine: Int,
        top: Int,
        contentHeight: Int,
    ): Int {
        val pageLines = endLine - startLine + 1
        if (pageLines <= MIN_LINES_PER_PAGE) return endLine

        val maxEmpty = (contentHeight * MAX_PARAGRAPH_PULL_FRACTION).toInt().coerceAtLeast(1)
        val fullBottom = layout.getLineBottom(endLine)
        val searchFloor = startLine + MIN_LINES_PER_PAGE - 1
        val lowerThird = maxOf(searchFloor, endLine - (pageLines / 3).coerceAtLeast(1))
        for (candidate in endLine downTo lowerThird) {
            if (!isParagraphEnd(layout, candidate)) continue
            val empty = fullBottom - layout.getLineBottom(candidate)
            if (empty <= maxEmpty) return candidate
        }
        return endLine
    }

    private fun keepHeadingWithBody(
        layout: StaticLayout,
        startLine: Int,
        endLine: Int,
        lineCount: Int,
        top: Int,
        contentHeight: Int,
    ): Int {
        if (endLine >= lineCount - 1) return endLine
        if (endLine - startLine + 1 <= MIN_LINES_PER_PAGE) return endLine
        if (!isShortLine(layout, endLine)) return endLine
        if (isParagraphEnd(layout, endLine)) return endLine

        val keepUntil = (endLine + HEADING_KEEP_WITH_NEXT).coerceAtMost(lineCount - 1)
        if (layout.getLineBottom(keepUntil) - top <= contentHeight) {
            return keepUntil
        }
        return endLine - 1
    }


    private fun isParagraphEnd(layout: StaticLayout, line: Int): Boolean {
        val text = layout.text
        val end = layout.getLineEnd(line)
        if (end >= text.length) return true
        if (end > 0 && text[end - 1] == '\n') return true
        if (line + 1 < layout.lineCount) {
            val ns = layout.getLineStart(line + 1)
            val ne = layout.getLineEnd(line + 1)
            if (ns < ne && text.subSequence(ns, ne).isBlank()) return true
        }
        return false
    }

    private fun isShortLine(layout: StaticLayout, line: Int): Boolean {
        val width = layout.width.coerceAtLeast(1)
        return layout.getLineWidth(line) < width * SHORT_LINE_FRACTION
    }
}

package com.reader.engine.format.epub

import com.reader.core.model.Block
import com.reader.core.model.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhtmlToBlocksTest {
    @Test
    fun whitespaceNormalizationRemapsInlineSpanOffsets() {
        val blocks = XhtmlToBlocks.parse(
            "<html><body><p>  Hello <strong>world   now</strong> !</p></body></html>",
            "chapter.xhtml",
        )

        val paragraph = blocks.single() as Block.Paragraph
        assertEquals("Hello world now !", paragraph.text)

        val bold = paragraph.spans.single() as Span.Bold
        assertEquals(6, bold.start)
        assertEquals(15, bold.end)
        assertTrue(bold.end <= paragraph.text.length)
    }

    @Test
    fun whitespaceNormalizationKeepsLinkRangesInsideFinalText() {
        val blocks = XhtmlToBlocks.parse(
            "<html><body><p> A <a href=\"https://example.com\"> linked   title </a> B </p></body></html>",
            "chapter.xhtml",
        )

        val paragraph = blocks.single() as Block.Paragraph
        assertTrue(paragraph.spans.all { it.start >= 0 && it.end <= paragraph.text.length })
        assertEquals("A linked title B", paragraph.text)
    }
}

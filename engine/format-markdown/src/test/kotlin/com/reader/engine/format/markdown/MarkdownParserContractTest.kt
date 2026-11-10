package com.reader.engine.format.markdown

import com.reader.core.model.Block
import com.reader.core.model.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserContractTest {
    @Test
    fun inlineSpanOffsetsUseUtf16AfterDelimiterRemoval() {
        val chapters = MarkdownParser.parseDocument("😀 **bold** text")
        val paragraph = chapters.single().second.single() as Block.Paragraph

        assertEquals("😀 bold text", paragraph.text)
        val bold = paragraph.spans.single() as Span.Bold
        assertEquals(3, bold.start)
        assertEquals(7, bold.end)
        assertTrue(bold.end <= paragraph.text.length)
    }

    @Test
    fun linksAndNestedInlineStylesStayWithinFinalText() {
        val chapters = MarkdownParser.parseDocument("A [linked title](https://example.com)")
        val paragraph = chapters.single().second.single() as Block.Paragraph

        assertTrue(paragraph.spans.all {
            it.start >= 0 && it.end >= it.start && it.end <= paragraph.text.length
        })
        assertEquals("A linked title", paragraph.text)
    }
}

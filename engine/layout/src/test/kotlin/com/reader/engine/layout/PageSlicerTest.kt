package com.reader.engine.layout

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.reader.core.model.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageSlicerTest {

    @Test
    fun slicesAreContiguousAndNeverCreateBlankOnlyPages() {
        val text = "First paragraph with enough words to wrap across multiple lines.\n" +
            "\n" +
            "Second paragraph with additional words to exercise paragraph boundaries.\n" +
            "\n\n" +
            "Third paragraph closes the sample."
        val layout = createLayout(text, width = 180)

        val pages = PageSlicer.slice(layout, contentHeight = 96)

        assertTrue(pages.isNotEmpty())
        assertTrue(PageSlicer.isValid(layout, pages))
        assertEquals(0, pages.first().startOffset)
        assertEquals(text.length, pages.last().endOffset)
        assertTrue(
            pages.all { slice ->
                layout.text.subSequence(slice.startOffset, slice.endOffset).isNotBlank()
            },
        )
    }

    @Test
    fun oversizedLineStillProducesAValidAdvancingPage() {
        val text = "W".repeat(500)
        val layout = createLayout(text, width = 40)

        val pages = PageSlicer.slice(layout, contentHeight = 1)

        assertEquals(1, pages.size)
        assertEquals(text.length, pages.single().endOffset)
        assertTrue(PageSlicer.isValid(layout, pages))
    }

    @Test
    fun configuredParagraphSpacingChangesMeasuredLayoutHeight() {
        val blocks = listOf(
            Block.Paragraph("First paragraph"),
            Block.Paragraph("Second paragraph"),
        )
        val compact = createLayout(DocumentTextBuilder.build(blocks, 0f), width = 400)
        val spaced = createLayout(DocumentTextBuilder.build(blocks, 24f), width = 400)

        assertTrue(spaced.height >= compact.height + 20)
    }

    @Test
    fun emptyDocumentProducesOneValidSlice() {
        val layout = createLayout("", width = 200)
        val pages = PageSlicer.slice(layout, contentHeight = 100)

        assertEquals(1, pages.size)
        assertEquals(0, pages.single().startOffset)
        assertEquals(0, pages.single().endOffset)
        assertTrue(PageSlicer.isValid(layout, pages))
    }

    private fun createLayout(text: CharSequence, width: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build()
    }
}

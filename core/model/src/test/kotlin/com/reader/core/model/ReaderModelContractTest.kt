package com.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderModelContractTest {
    @Test
    fun locatorUsesNonNegativeZeroBasedCoordinates() {
        val locator = Locator(chapter = 2, offset = 15, progression = 0.5f)

        assertEquals(2, locator.chapter)
        assertEquals(15, locator.offset)
        assertEquals(0.5f, locator.progression)
    }

    @Test
    fun locatorRejectsNegativeCoordinatesAndInvalidProgression() {
        assertThrows(IllegalArgumentException::class.java) { Locator(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { Locator(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { Locator(0, 0, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Locator(0, 0, 1.01f) }
    }

    @Test
    fun spansUseValidHalfOpenUtf16Ranges() {
        val block = Block.Paragraph(
            text = "A😀B",
            spans = listOf(Span.Bold(start = 1, end = 3)),
        )

        assertEquals(1, (block as Block.Paragraph).spans.single().start)
        assertThrows(IllegalArgumentException::class.java) {
            Block.Paragraph("text", listOf(Span.Italic(1, 5)))
        }
        assertThrows(IllegalArgumentException::class.java) { Span.Code(-1, 2) }
        assertThrows(IllegalArgumentException::class.java) { Span.Code(3, 2) }
    }

    @Test
    fun headingAndChapterIndicesAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { Block.Heading(0, "title") }
        assertThrows(IllegalArgumentException::class.java) { Block.Heading(7, "title") }
        assertThrows(IllegalArgumentException::class.java) { Chapter(-1, null, emptyList()) }
    }
}

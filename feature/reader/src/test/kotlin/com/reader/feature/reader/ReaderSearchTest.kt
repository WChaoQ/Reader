package com.reader.feature.reader

import com.reader.core.model.Block
import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import com.reader.engine.layout.DocumentTextBuilder
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderSearchTest {

    @Test
    fun sourceFailureDoesNotSilentlyReturnPartialSearchResults() = runBlocking {
        val source = TestBookSource(chapterCount = 2) { index ->
            if (index == 0) {
                chapter(index, "needle appears before the provider failure")
            } else {
                throw SecurityException("provider permission revoked")
            }
        }

        val failure = try {
            searchBookSource(source, "needle")
            null
        } catch (caught: SecurityException) {
            caught
        }

        assertEquals("provider permission revoked", failure?.message)
    }

    @Test
    fun cancellationIsPropagatedWithoutBeingConvertedToSearchFailure() = runBlocking {
        val expected = CancellationException("search cancelled")
        val source = TestBookSource(chapterCount = 1) {
            throw expected
        }

        val actual = try {
            searchBookSource(source, "needle")
            null
        } catch (caught: CancellationException) {
            caught
        }

        assertSame(expected, actual)
    }

    @Test
    fun successfulSearchReturnsStableChapterAndTextOffset() = runBlocking {
        val source = TestBookSource(chapterCount = 2) { index ->
            chapter(index, if (index == 0) "nothing here" else "before needle after")
        }

        val hits = searchBookSource(source, " needle ")

        assertEquals(1, hits.size)
        assertEquals(1, hits.single().locator.chapter)
        assertEquals("before needle after".indexOf("needle"), hits.single().locator.offset)
        assertTrue(hits.single().snippet.contains("needle"))
        assertEquals("Chapter 2", hits.single().chapterTitle)
    }

    @Test
    fun nestedAndResourceTextUseOffsetsFromTheActualLayoutText() = runBlocking {
        val blocks = listOf(
            Block.Paragraph("intro"),
            Block.Quote(listOf(Block.Paragraph("quoted needle"))),
            Block.ListItem(
                ordered = true,
                index = 1,
                children = listOf(Block.Paragraph("listed needle")),
            ),
            Block.Image(ResourceRef("image.png"), alt = "image needle"),
        )
        val renderedText = DocumentTextBuilder.build(blocks).toString()
        val source = TestBookSource(chapterCount = 1) {
            Chapter(index = 0, title = "Nested content", blocks = blocks)
        }

        val hits = searchBookSource(source, "needle")

        assertEquals(3, hits.size)
        assertEquals(
            listOf("quoted needle", "listed needle", "image needle").map { renderedText.indexOf(it) },
            hits.map { it.locator.offset },
        )
    }

    private class TestBookSource(
        override val chapterCount: Int,
        private val load: suspend (Int) -> Chapter,
    ) : BookSource {
        override val format: BookFormat = BookFormat.EPUB

        override suspend fun metadata() = BookMetadata(title = "Search fixture")

        override suspend fun toc(): List<TocEntry> = emptyList()

        override suspend fun loadChapter(index: Int): Chapter = load(index)

        override suspend fun openResource(ref: ResourceRef) = ByteArrayInputStream(byteArrayOf())

        override fun close() = Unit
    }

    private fun chapter(index: Int, text: String) = Chapter(
        index = index,
        title = "Chapter ${index + 1}",
        blocks = listOf(Block.Paragraph(text)),
    )
}

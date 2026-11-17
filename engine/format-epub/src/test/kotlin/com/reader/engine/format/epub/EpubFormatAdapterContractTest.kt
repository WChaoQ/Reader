package com.reader.engine.format.epub

import com.reader.core.model.Block
import com.reader.core.model.ResourceRef
import com.reader.core.model.Span
import com.reader.engine.format.DocumentHandle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubFormatAdapterContractTest {

    @Test
    fun opensRealEpubAndExposesMetadataTocChaptersAndResources() = runBlocking {
        val file = fixtureFile()
        val handler = EpubFormatHandler(RuntimeEnvironment.getApplication())
        val source = handler.open(DocumentHandle.fromFile(file))

        try {
            assertEquals(2, source.chapterCount)

            val metadata = source.metadata()
            assertEquals("Reader EPUB Contract Fixture", metadata.title)
            assertEquals("Reader Test Suite", metadata.author)
            assertEquals("en", metadata.language)

            val toc = source.toc()
            assertEquals(1, toc.size)
            assertEquals("Part One", toc.single().title)
            assertEquals(0, toc.single().locator.chapter)
            assertEquals(
                listOf("Chapter One", "Chapter Two"),
                toc.single().children.map { it.title },
            )
            assertEquals(
                listOf(0, 1),
                toc.single().children.map { it.locator.chapter },
            )

            val firstChapter = source.loadChapter(0)
            assertEquals(0, firstChapter.index)
            assertEquals("Chapter One", firstChapter.title)

            val styledParagraph = firstChapter.blocks
                .filterIsInstance<Block.Paragraph>()
                .first { it.text.contains("Hello") }
            assertTrue(styledParagraph.text.contains("😀"))

            val bold = styledParagraph.spans.filterIsInstance<Span.Bold>().single()
            val italic = styledParagraph.spans.filterIsInstance<Span.Italic>().single()
            val link = styledParagraph.spans.filterIsInstance<Span.Link>().single()
            assertEquals("bold and italic", styledParagraph.text.substring(bold.start, bold.end))
            assertEquals("and italic", styledParagraph.text.substring(italic.start, italic.end))
            assertEquals("next chapter", styledParagraph.text.substring(link.start, link.end))
            assertTrue(styledParagraph.spans.all {
                it.start >= 0 && it.end <= styledParagraph.text.length
            })

            val unicodeParagraph = firstChapter.blocks
                .filterIsInstance<Block.Paragraph>()
                .first { it.text.startsWith("Unicode:") }
            assertEquals("Unicode: 中文 Ελληνικά café 😀", unicodeParagraph.text)

            val image = firstChapter.blocks.filterIsInstance<Block.Image>().single()
            assertEquals("One pixel", image.alt)
            val imageBytes = source.openResource(image.ref).use { it.readBytes() }
            assertArrayEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                imageBytes.copyOfRange(0, 8),
            )

            val packageRoot = image.ref.href.substringBeforeLast("Images/pixel.png").trimEnd('/')
            val fontHref = listOf(packageRoot, "Fonts/fixture.ttf")
                .filter { it.isNotEmpty() }
                .joinToString("/")
            val fontBytes = source.openResource(ResourceRef(fontHref)).use { it.readBytes() }
            assertTrue(fontBytes.size > 500)
            assertArrayEquals(byteArrayOf(0, 1, 0, 0), fontBytes.copyOfRange(0, 4))

            val secondChapter = source.loadChapter(1)
            assertEquals("Chapter Two", secondChapter.title)
            assertTrue(
                secondChapter.blocks.filterIsInstance<Block.Paragraph>()
                    .any { it.text == "Second chapter body." },
            )

            val invalidIndexFailure = runCatching { source.loadChapter(2) }.exceptionOrNull()
            assertTrue(invalidIndexFailure is IllegalArgumentException)
        } finally {
            source.close()
            source.close()
            file.delete()
        }

        val closedFailure = runCatching { source.metadata() }.exceptionOrNull()
        assertTrue(closedFailure is IllegalStateException)
    }

    @Test
    fun rejectsCorruptEpubContainer() = runBlocking {
        val file = File.createTempFile("reader-corrupt-", ".epub")
        file.writeText("this is not a ZIP or EPUB container")
        try {
            val handler = EpubFormatHandler(RuntimeEnvironment.getApplication())
            val failure = runCatching {
                handler.open(DocumentHandle.fromFile(file))
            }.exceptionOrNull()
            assertNotNull("A corrupt EPUB must not produce a BookSource", failure)
        } finally {
            file.delete()
        }
    }

    private fun fixtureFile(): File {
        val fixture = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("epub/reader-contract.epub"),
        ) { "Missing real EPUB contract fixture" }
        return File.createTempFile("reader-contract-", ".epub").apply {
            fixture.use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

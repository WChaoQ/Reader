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
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
            println("EPUB_FIXTURE_METADATA=$metadata")
            assertEquals("Reader EPUB Contract Fixture", metadata.title)
            assertEquals("Reader Test Suite", metadata.author)
            assertEquals("en", metadata.language)

            val toc = source.toc()
            println("EPUB_FIXTURE_TOC=$toc")
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
            println("EPUB_FIXTURE_CHAPTER_1=$firstChapter")
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

            val fontHref = image.ref.href.substringBeforeLast('/') + "/fixture.ttf"
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
        val image = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p2sAAAAASUVORK5CYII=",
        )
        val font = ByteArray(512).apply {
            this[0] = 0
            this[1] = 1
            this[2] = 0
            this[3] = 0
        }
        val entries = listOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.US_ASCII),
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray(),
            "EPUB/package.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="pub-id">urn:reader:fixture:epub-contract</dc:identifier>
                    <dc:title>Reader EPUB Contract Fixture</dc:title>
                    <dc:creator>Reader Test Suite</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="chapter-one" href="Text/chapter-1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-two" href="Text/chapter-2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="pixel" href="Resources/pixel.png" media-type="image/png"/>
                    <item id="font" href="Resources/fixture.ttf" media-type="font/ttf"/>
                    <item id="style" href="Styles/book.css" media-type="text/css"/>
                  </manifest>
                  <spine>
                    <itemref idref="chapter-one"/>
                    <itemref idref="chapter-two"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray(),
            "EPUB/nav.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>Contents</title></head>
                  <body>
                    <nav epub:type="toc" id="toc">
                      <h1>Contents</h1>
                      <ol>
                        <li><a href="Text/chapter-1.xhtml#part-one">Part One</a>
                          <ol>
                            <li><a href="Text/chapter-1.xhtml#chapter-one">Chapter One</a></li>
                            <li><a href="Text/chapter-2.xhtml#chapter-two">Chapter Two</a></li>
                          </ol>
                        </li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent().toByteArray(),
            "EPUB/Text/chapter-1.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter One</title></head>
                  <body>
                    <h1 id="chapter-one">Chapter One</h1>
                    <p>  Hello   <strong>bold <em>and italic</em></strong>   world 😀
                      <a href="chapter-2.xhtml#chapter-two"> next   chapter </a>
                    </p>
                    <p>Unicode: 中文 Ελληνικά café 😀</p>
                    <img src="../Resources/pixel.png" alt="One pixel"/>
                  </body>
                </html>
            """.trimIndent().toByteArray(Charsets.UTF_8),
            "EPUB/Text/chapter-2.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter Two</title></head>
                  <body><h2 id="chapter-two">Chapter Two</h2><p>Second chapter body.</p></body>
                </html>
            """.trimIndent().toByteArray(),
            "EPUB/Resources/pixel.png" to image,
            "EPUB/Resources/fixture.ttf" to font,
            "EPUB/Styles/book.css" to "body { font-family: 'Reader Fixture'; }".toByteArray(),
        )

        val file = File.createTempFile("reader-contract-", ".epub")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}

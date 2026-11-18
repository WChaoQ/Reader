package com.reader.core.data

import android.content.Context
import android.net.Uri
import com.reader.core.common.ReaderDispatchers
import com.reader.core.database.di.DatabaseModule
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.core.storage.BookFileStore
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import com.reader.engine.format.FormatHandler
import com.reader.engine.format.FormatRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyBookSourceRepositoryTest {

    @Test
    fun opensLegacyEpubFromOriginalFileWithoutCreatingAnotherCopy() = runBlocking {
        withRepositories { _, fileStore, bookRepository, _ ->
            val book = legacyBook(BookFormat.EPUB)
            bookRepository.upsert(book)

            val original = fileStore.originalFile(book.id, "epub")
            createMinimalEpub(original)
            val originalBytes = original.readBytes()
            val handler = RecordingFormatHandler(BookFormat.EPUB)
            val repository = BookSourceRepository(
                context = RuntimeEnvironment.getApplication(),
                bookRepository = bookRepository,
                formatRegistry = FormatRegistry(setOf(handler)),
                dispatchers = TestReaderDispatchers,
            )

            val source = repository.open(book)
            try {
                val opened = requireNotNull(handler.openedDocument)
                assertEquals(Uri.fromFile(original), opened.uri)
                assertEquals(original.absolutePath, opened.uri.path)
                assertEquals("original.epub", opened.displayName)
                assertEquals(original.length(), opened.size)
                assertEquals(BookFormat.EPUB, source.format)
                assertTrue(original.isFile)
                assertEquals(originalBytes.toList(), original.readBytes().toList())
                assertEquals(
                    listOf("original.epub"),
                    original.parentFile?.list()?.toList(),
                )
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun missingLegacyFileReturnsReselectErrorAndPreservesBookAndProgress() = runBlocking {
        withRepositories { _, _, bookRepository, database ->
            val book = legacyBook(BookFormat.EPUB)
            val locator = Locator(chapter = 4, offset = 912, progression = 0.4f)
            bookRepository.upsert(book)
            val progressRepository = ProgressRepository(
                progressDao = database.progressDao(),
                dispatchers = TestReaderDispatchers,
            )
            progressRepository.save(book.id, locator)

            val repository = BookSourceRepository(
                context = RuntimeEnvironment.getApplication(),
                bookRepository = bookRepository,
                formatRegistry = FormatRegistry(setOf(RecordingFormatHandler(BookFormat.EPUB))),
                dispatchers = TestReaderDispatchers,
            )

            val failure = runCatching { repository.open(book) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure is IllegalArgumentException)
            assertEquals("原文件不存在，请重新选择文档", failure?.message)

            val persistedBook = bookRepository.getBook(book.id)
            assertNotNull(persistedBook)
            assertNull(persistedBook?.sourceUri)
            assertEquals(locator, progressRepository.get(book.id))
        }
    }

    @Test
    fun opensLegacyPdfAsSeekableDescriptorOwnedByCaller() = runBlocking {
        withRepositories { _, fileStore, bookRepository, _ ->
            val book = legacyBook(BookFormat.PDF)
            bookRepository.upsert(book)

            val original = fileStore.originalFile(book.id, "pdf")
            original.parentFile?.mkdirs()
            original.writeBytes("%PDF-1.4\nlegacy-fixture\n".toByteArray())
            val repository = BookSourceRepository(
                context = RuntimeEnvironment.getApplication(),
                bookRepository = bookRepository,
                formatRegistry = FormatRegistry(emptySet()),
                dispatchers = TestReaderDispatchers,
            )

            val descriptor = repository.openPdfDescriptor(book)
            try {
                assertEquals(original.length(), descriptor.statSize)
                assertTrue(descriptor.fileDescriptor.valid())
                assertTrue(original.isFile)
            } finally {
                descriptor.close()
            }
        }
    }

    private suspend fun withRepositories(
        block: suspend (
            Context,
            BookFileStore,
            BookRepository,
            com.reader.core.database.ReaderDatabase,
        ) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        val database = DatabaseModule.provideDatabase(context)
        val fileStore = BookFileStore(context)
        try {
            val repository = BookRepository(
                context = context,
                bookDao = database.bookDao(),
                fileStore = fileStore,
                dispatchers = TestReaderDispatchers,
            )
            block(context, fileStore, repository, database)
        } finally {
            database.close()
            context.deleteDatabase(DATABASE_NAME)
            fileStore.deleteBook(TEST_BOOK_ID_PREFIX)
        }
    }

    private fun legacyBook(format: BookFormat): Book = Book(
        id = TEST_BOOK_ID_PREFIX,
        title = "Legacy ${format.name}",
        author = "Reader Test",
        format = format,
        fileSize = 128L,
        coverPath = null,
        importedAt = 1_700_000_000_000L,
        lastReadAt = null,
        sourceUri = null,
    )

    private fun createMinimalEpub(file: File) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            val crc = CRC32().apply { update(mimetype) }
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    this.crc = crc.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">legacy</dc:identifier><dc:title>Legacy</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>"""
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/chapter.xhtml"))
            zip.write(
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Legacy</title></head><body><p>Legacy source fixture</p></body></html>"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }
    }

    private class RecordingFormatHandler(
        override val format: BookFormat,
    ) : FormatHandler {
        var openedDocument: DocumentHandle? = null

        override fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean = true

        override suspend fun open(document: DocumentHandle): BookSource {
            openedDocument = document
            return TestBookSource(format)
        }
    }

    private class TestBookSource(
        override val format: BookFormat,
    ) : BookSource {
        override val chapterCount: Int = 1
        override suspend fun metadata() = BookMetadata(title = "Legacy fixture")
        override suspend fun toc(): List<TocEntry> = emptyList()
        override suspend fun loadChapter(index: Int) = Chapter(index, "Chapter", emptyList())
        override suspend fun openResource(ref: ResourceRef) = ByteArrayInputStream(byteArrayOf())
        override fun close() = Unit
    }

    private object TestReaderDispatchers : ReaderDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    private companion object {
        const val DATABASE_NAME = "reader.db"
        const val TEST_BOOK_ID_PREFIX = "legacy-source-test"
    }
}

package com.reader.app.saf

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.core.common.ReaderDispatchers
import com.reader.core.common.Result
import com.reader.core.common.getOrNull
import com.reader.core.data.BookRepository
import com.reader.core.data.BookSourceRepository
import com.reader.core.data.BookmarkRepository
import com.reader.core.data.OpenDocumentUseCase
import com.reader.core.data.ProgressRepository
import com.reader.core.database.ReaderDatabase
import com.reader.core.model.Locator
import com.reader.core.storage.BookFileStore
import com.reader.engine.format.FormatRegistry
import com.reader.engine.format.epub.EpubFormatHandler
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafDocumentLifecycleInstrumentedTest {

    @Test
    fun openMultipleDocumentsContractUsesOpenDocumentForSupportedMimeTypes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supportedMimeTypes = arrayOf(TestDocumentsProvider.MIME_TYPE)
        val intent = ActivityResultContracts.OpenMultipleDocuments()
            .createIntent(context, supportedMimeTypes)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertEquals(supportedMimeTypes.toList(), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun persistedSafGrantAndReaderDataSurviveDatabaseRecreationAndCanBeReauthorized() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val providerContext = instrumentation.context
        val resolver = targetContext.contentResolver
        val uri = DocumentsContract.buildDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.DOCUMENT_ID,
        )
        val fixture = TestDocumentsProvider.fixtureFile(providerContext)
        fixture.parentFile?.mkdirs()
        createMinimalEpub(fixture)

        var database = openDatabase(targetContext)
        try {
            grantReadAccess(providerContext, targetContext.packageName, uri)
            val first = repositories(targetContext, database)
            val registry = FormatRegistry(setOf(EpubFormatHandler(targetContext)))
            val openDocument = OpenDocumentUseCase(
                context = targetContext,
                bookRepository = first.books,
                formatRegistry = registry,
                dispatchers = TestDispatchers,
            )

            val result = openDocument(uri)
            val book = result.getOrNull() ?: throw IllegalStateException(
                "The actual EPUB adapter must accept the SAF descriptor: $result",
                (result as Result.Error).exception,
            )
            assertEquals("SAF Test Book", book.title)
            assertEquals(uri.toString(), book.sourceUri)
            assertTrue(
                resolver.persistedUriPermissions.any { permission ->
                    permission.uri == uri && permission.isReadPermission && !permission.isWritePermission
                },
            )
            assertFalse(BookFileStore(targetContext).originalFile(book.id, "epub").exists())

            val locator = Locator(chapter = 0, offset = 18, progression = 0.35f)
            val bookmarkLocator = Locator(chapter = 0, offset = 18)
            first.progress.save(book.id, locator)
            first.bookmarks.add(book.id, bookmarkLocator, "Persisted SAF bookmark")

            database.close()
            database = openDatabase(targetContext)
            val reopened = repositories(targetContext, database)

            val persistedBook = reopened.books.getBook(book.id)
            assertNotNull(persistedBook)
            assertEquals(uri.toString(), persistedBook?.sourceUri)
            assertEquals(locator, reopened.progress.get(book.id))
            val bookmarks = reopened.bookmarks.observe(book.id).first()
            assertEquals(1, bookmarks.size)
            assertEquals(bookmarkLocator, bookmarks.single().locator)

            val sourceRepository = BookSourceRepository(
                context = targetContext,
                bookRepository = reopened.books,
                formatRegistry = registry,
                dispatchers = TestDispatchers,
            )
            val source = sourceRepository.open(requireNotNull(persistedBook))
            try {
                assertEquals(1, source.chapterCount)
                assertEquals("SAF Test Book", source.metadata().title)
                assertTrue(source.loadChapter(0).blocks.isNotEmpty())
            } finally {
                source.close()
            }

            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            revokeReadAccess(providerContext, targetContext.packageName, uri)
            val revokedFailure = runCatching {
                sourceRepository.open(requireNotNull(persistedBook))
            }.exceptionOrNull()
            assertTrue(
                "Revoked provider access must fail explicitly: $revokedFailure",
                revokedFailure is SecurityException,
            )

            assertNotNull(reopened.books.getBook(book.id))
            assertEquals(locator, reopened.progress.get(book.id))
            assertEquals(bookmarkLocator, reopened.bookmarks.observe(book.id).first().single().locator)

            grantReadAccess(providerContext, targetContext.packageName, uri)
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val recoveredSource = sourceRepository.open(requireNotNull(persistedBook))
            try {
                assertEquals(1, recoveredSource.chapterCount)
                assertTrue(recoveredSource.loadChapter(0).blocks.isNotEmpty())
            } finally {
                recoveredSource.close()
            }
        } finally {
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            revokeReadAccess(providerContext, targetContext.packageName, uri)
            database.close()
            targetContext.deleteDatabase(DATABASE_NAME)
            fixture.delete()
        }
    }

    private fun grantReadAccess(
        providerContext: android.content.Context,
        targetPackage: String,
        uri: Uri,
    ) {
        providerContext.contentResolver.call(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.METHOD_GRANT_READ,
            null,
            Bundle().apply {
                putString(TestDocumentsProvider.EXTRA_TARGET_PACKAGE, targetPackage)
                putString(TestDocumentsProvider.EXTRA_URI, uri.toString())
            },
        )
    }

    private fun revokeReadAccess(
        providerContext: android.content.Context,
        targetPackage: String,
        uri: Uri,
    ) {
        providerContext.contentResolver.call(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.METHOD_REVOKE_READ,
            null,
            Bundle().apply {
                putString(TestDocumentsProvider.EXTRA_TARGET_PACKAGE, targetPackage)
                putString(TestDocumentsProvider.EXTRA_URI, uri.toString())
            },
        )
    }

    private fun openDatabase(context: android.content.Context): ReaderDatabase =
        Room.databaseBuilder(context, ReaderDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .build()

    private fun repositories(
        context: android.content.Context,
        database: ReaderDatabase,
    ) = TestRepositories(
        books = BookRepository(
            context = context,
            bookDao = database.bookDao(),
            fileStore = BookFileStore(context),
            dispatchers = TestDispatchers,
        ),
        progress = ProgressRepository(database.progressDao(), TestDispatchers),
        bookmarks = BookmarkRepository(database.bookmarkDao()),
    )

    private fun createMinimalEpub(file: File) {
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
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">saf-fixture</dc:identifier><dc:title>SAF Test Book</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>"""
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/chapter.xhtml"))
            zip.write(
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter One</title></head><body><p>Persisted SAF chapter content for the reader integration test.</p></body></html>"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }
    }

    private data class TestRepositories(
        val books: BookRepository,
        val progress: ProgressRepository,
        val bookmarks: BookmarkRepository,
    )

    private object TestDispatchers : ReaderDispatchers {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    private companion object {
        const val DATABASE_NAME = "reader-saf-instrumentation.db"
    }
}

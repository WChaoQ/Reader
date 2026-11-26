package com.reader.app.saf

import android.content.ContentResolver
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertEquals("*/*", intent.type)
        assertEquals(supportedMimeTypes.toList(), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun grantControlRejectsInstrumentationPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val instrumentationContext = instrumentation.context
        assertNotEquals(targetContext.packageName, instrumentationContext.packageName)

        val uri = DocumentsContract.buildDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.DOCUMENT_ID,
        )
        val failure = runCatching {
            instrumentationContext.contentResolver.call(
                uri,
                TestDocumentsProvider.METHOD_GRANT_READ,
                null,
                null,
            )
        }.exceptionOrNull()

        assertTrue(
            "Only the target Reader package may request fixture grants: $failure",
            failure is SecurityException,
        )
    }

    @Test
    fun persistedSafGrantAndReaderDataSurviveDatabaseRecreationAndCanBeReauthorized() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val resolver = targetContext.contentResolver
        val uri = DocumentsContract.buildDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.DOCUMENT_ID,
        )
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        runCatching { revokeReadAccess(resolver) }
        targetContext.deleteDatabase(DATABASE_NAME)

        var database = openDatabase(targetContext)
        try {
            val deniedBeforeGrant = runCatching {
                resolver.openFileDescriptor(uri, "r")
            }.exceptionOrNull()
            assertTrue(
                "The target app must not open the fixture before receiving a URI grant: $deniedBeforeGrant",
                deniedBeforeGrant is SecurityException,
            )
            grantReadAccess(resolver)
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
            revokeReadAccess(resolver)
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

            grantReadAccess(resolver)
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
            runCatching { revokeReadAccess(resolver) }
            database.close()
            targetContext.deleteDatabase(DATABASE_NAME)
        }
    }

    private fun grantReadAccess(resolver: ContentResolver) {
        val result = resolver.call(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.METHOD_GRANT_READ,
            null,
            null,
        )
        assertTrue(
            "The provider must confirm the read-only persistable grant",
            result?.getBoolean(TestDocumentsProvider.KEY_SUCCESS) == true,
        )
    }

    private fun revokeReadAccess(resolver: ContentResolver) {
        val result = resolver.call(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.METHOD_REVOKE_READ,
            null,
            null,
        )
        assertTrue(
            "The provider must confirm grant revocation",
            result?.getBoolean(TestDocumentsProvider.KEY_SUCCESS) == true,
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

package com.reader.core.data

import android.database.sqlite.SQLiteDatabase
import com.reader.core.common.ReaderDispatchers
import com.reader.core.database.di.DatabaseModule
import com.reader.core.model.Locator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

/**
 * Verifies that a v1 database remains usable through the production DAO and
 * repository mapping paths after the production v1 -> v2 Room migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyReaderDataMigrationIntegrationTest {

    @Test
    fun migratedProgressAndBookmarksAreReadableThroughRepositories() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        createVersionOneDatabase(databaseFile)

        val database = DatabaseModule.provideDatabase(context)
        try {
            val bookEntity = database.bookDao().getById(LEGACY_BOOK_ID)
            assertNotNull(bookEntity)
            val book = requireNotNull(bookEntity).toModel()
            assertEquals("Legacy EPUB", book.title)
            assertNull("Legacy records must keep the absent SAF URI", book.sourceUri)

            val progress = ProgressRepository(
                progressDao = database.progressDao(),
                dispatchers = TestReaderDispatchers,
            ).get(LEGACY_BOOK_ID)

            assertEquals(Locator(chapter = 6, offset = 18432, progression = 0.625f), progress)

            val bookmarks = BookmarkRepository(database.bookmarkDao())
                .observe(LEGACY_BOOK_ID)
                .first()

            assertEquals(1, bookmarks.size)
            val bookmark = bookmarks.single()
            assertEquals(LEGACY_BOOKMARK_ID, bookmark.id)
            assertEquals(LEGACY_BOOK_ID, bookmark.bookId)
            assertEquals(Locator(chapter = 6, offset = 18480), bookmark.locator)
            assertEquals("A saved legacy passage", bookmark.snippet)
            assertEquals(1_700_000_000_100L, bookmark.createdAt)
        } finally {
            database.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    private fun createVersionOneDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `author` TEXT,
                    `format` TEXT NOT NULL,
                    `fileSize` INTEGER NOT NULL,
                    `coverPath` TEXT,
                    `importedAt` INTEGER NOT NULL,
                    `lastReadAt` INTEGER,
                    `chapterCount` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `progress` (
                    `bookId` TEXT NOT NULL,
                    `chapter` INTEGER NOT NULL,
                    `offset` INTEGER NOT NULL,
                    `progression` REAL NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`bookId`),
                    FOREIGN KEY(`bookId`) REFERENCES `book`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bookmark` (
                    `id` TEXT NOT NULL,
                    `bookId` TEXT NOT NULL,
                    `chapter` INTEGER NOT NULL,
                    `offset` INTEGER NOT NULL,
                    `snippet` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`bookId`) REFERENCES `book`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `highlight` (
                    `id` TEXT NOT NULL,
                    `bookId` TEXT NOT NULL,
                    `chapter` INTEGER NOT NULL,
                    `start` INTEGER NOT NULL,
                    `end` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `color` INTEGER NOT NULL,
                    `note` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`bookId`) REFERENCES `book`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_lastReadAt` " +
                    "ON `book` (`lastReadAt`)",
            )
            legacy.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_title` " +
                    "ON `book` (`title`)",
            )
            legacy.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bookmark_bookId` " +
                    "ON `bookmark` (`bookId`)",
            )
            legacy.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_highlight_bookId_chapter` " +
                    "ON `highlight` (`bookId`, `chapter`)",
            )
            legacy.execSQL(
                """
                INSERT INTO `book` (
                    `id`, `title`, `author`, `format`, `fileSize`,
                    `coverPath`, `importedAt`, `lastReadAt`, `chapterCount`, `state`
                ) VALUES (
                    '$LEGACY_BOOK_ID', 'Legacy EPUB', 'Reader Test', 'EPUB', 4096,
                    NULL, 1699999999000, 1700000000000, 12, 'ready'
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                INSERT INTO `progress`
                    (`bookId`, `chapter`, `offset`, `progression`, `updatedAt`)
                VALUES ('$LEGACY_BOOK_ID', 6, 18432, 0.625, 1700000000000)
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                INSERT INTO `bookmark`
                    (`id`, `bookId`, `chapter`, `offset`, `snippet`, `createdAt`)
                VALUES (
                    '$LEGACY_BOOKMARK_ID', '$LEGACY_BOOK_ID', 6, 18480,
                    'A saved legacy passage', 1700000000100
                )
                """.trimIndent(),
            )
            legacy.setVersion(1)
        }
    }

    private object TestReaderDispatchers : ReaderDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    private companion object {
        const val DATABASE_NAME = "reader.db"
        const val LEGACY_BOOK_ID = "legacy-book"
        const val LEGACY_BOOKMARK_ID = "legacy-bookmark"
    }
}

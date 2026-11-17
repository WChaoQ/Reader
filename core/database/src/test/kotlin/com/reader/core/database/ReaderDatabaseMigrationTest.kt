package com.reader.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reader.core.database.di.DatabaseModule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderDatabaseMigrationTest {

    @Test
    fun migrationFromVersionOnePreservesProgressAndBookmarks() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        createVersionOneDatabase(databaseFile)

        val database = DatabaseModule.provideDatabase(context)
        try {
            val migrated = database.openHelper.writableDatabase

            migrated.query(
                "SELECT sourceUri FROM book WHERE id = 'legacy-book'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }

            migrated.query(
                "SELECT chapter, `offset`, progression, updatedAt " +
                    "FROM progress WHERE bookId = 'legacy-book'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(6, cursor.getInt(0))
                assertEquals(18432, cursor.getInt(1))
                assertEquals(0.625f, cursor.getFloat(2), 0.0001f)
                assertEquals(1_700_000_000_000L, cursor.getLong(3))
                assertTrue(!cursor.moveToNext())
            }

            migrated.query(
                "SELECT id, chapter, `offset`, snippet, createdAt " +
                    "FROM bookmark WHERE id = 'legacy-bookmark'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-bookmark", cursor.getString(0))
                assertEquals(6, cursor.getInt(1))
                assertEquals(18480, cursor.getInt(2))
                assertEquals("A saved legacy passage", cursor.getString(3))
                assertEquals(1_700_000_000_100L, cursor.getLong(4))
                assertTrue(!cursor.moveToNext())
            }

            migrated.query("PRAGMA foreign_key_check").use { cursor ->
                assertTrue("Migration must preserve valid foreign keys", !cursor.moveToFirst())
            }
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
                    'legacy-book', 'Legacy EPUB', 'Reader Test', 'EPUB', 4096,
                    NULL, 1699999999000, 1700000000000, 12, 'ready'
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                INSERT INTO `progress`
                    (`bookId`, `chapter`, `offset`, `progression`, `updatedAt`)
                VALUES ('legacy-book', 6, 18432, 0.625, 1700000000000)
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                INSERT INTO `bookmark`
                    (`id`, `bookId`, `chapter`, `offset`, `snippet`, `createdAt`)
                VALUES (
                    'legacy-bookmark', 'legacy-book', 6, 18480,
                    'A saved legacy passage', 1700000000100
                )
                """.trimIndent(),
            )
            legacy.setVersion(1)
        }
    }

    private companion object {
        const val DATABASE_NAME = "reader.db"
    }
}

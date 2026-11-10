package com.reader.core.data

import com.reader.core.database.BookmarkEntity
import com.reader.core.database.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistedLocatorMapperTest {
    @Test
    fun progressMapperBoundsLegacyCoordinatesAndProgression() {
        val locator = ProgressEntity(
            bookId = "book",
            chapter = -4,
            offset = -20,
            progression = Float.NaN,
            updatedAt = 0L,
        ).toLocator()

        assertEquals(0, locator.chapter)
        assertEquals(0, locator.offset)
        assertEquals(0f, locator.progression, 0f)
    }

    @Test
    fun progressMapperClampsOutOfRangeProgression() {
        val locator = ProgressEntity(
            bookId = "book",
            chapter = 2,
            offset = 10,
            progression = 1.5f,
            updatedAt = 0L,
        ).toLocator()

        assertEquals(1f, locator.progression, 0f)
    }

    @Test
    fun bookmarkMapperBoundsLegacyCoordinates() {
        val bookmark = BookmarkEntity(
            id = "bookmark",
            bookId = "book",
            chapter = -2,
            offset = -5,
            snippet = "",
            createdAt = 0L,
        ).toModel()

        assertEquals(0, bookmark.locator.chapter)
        assertEquals(0, bookmark.locator.offset)
    }
}

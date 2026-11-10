package com.reader.core.data

import com.reader.core.database.BookmarkDao
import com.reader.core.database.BookmarkEntity
import com.reader.core.model.Bookmark
import com.reader.core.model.Locator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(
    private val dao: BookmarkDao,
) {
    fun observe(bookId: String): Flow<List<Bookmark>> =
        dao.observeByBook(bookId).map { entities -> entities.map(BookmarkEntity::toModel) }

    suspend fun add(bookId: String, locator: Locator, snippet: String): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            BookmarkEntity(
                id = id,
                bookId = bookId,
                chapter = locator.chapter,
                offset = locator.offset,
                snippet = snippet.take(200),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun delete(id: String) = dao.delete(id)
}

private fun BookmarkEntity.toModel() = Bookmark(
    id = id,
    bookId = bookId,
    locator = Locator(chapter = chapter.coerceAtLeast(0), offset = offset.coerceAtLeast(0)),
    snippet = snippet,
    createdAt = createdAt,
)

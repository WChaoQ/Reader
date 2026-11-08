package com.reader.core.data

import com.reader.core.database.HighlightDao
import com.reader.core.database.HighlightEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HighlightRepository @Inject constructor(
    private val dao: HighlightDao,
) {
    fun observeChapter(bookId: String, chapter: Int): Flow<List<HighlightEntity>> =
        dao.observeByChapter(bookId, chapter)

    suspend fun add(
        bookId: String,
        chapter: Int,
        start: Int,
        end: Int,
        text: String,
        color: Int = 0xFFFFF59D.toInt(),
        note: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            HighlightEntity(
                id = id,
                bookId = bookId,
                chapter = chapter,
                start = start,
                end = end,
                text = text.take(500),
                color = color,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun delete(id: String) = dao.delete(id)
}

package com.reader.core.data

import com.reader.core.database.BookEntity
import com.reader.core.database.ProgressEntity
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import com.reader.core.model.Locator

fun BookEntity.toModel(): Book = Book(
    id = id,
    title = title,
    author = author,
    format = runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.TXT),
    fileSize = fileSize,
    coverPath = coverPath,
    importedAt = importedAt,
    lastReadAt = lastReadAt,
    sourceUri = sourceUri,
)

fun Book.toEntity(chapterCount: Int = 0, state: String = "ready"): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    format = format.name,
    fileSize = fileSize,
    coverPath = coverPath,
    importedAt = importedAt,
    lastReadAt = lastReadAt,
    chapterCount = chapterCount,
    state = state,
    sourceUri = sourceUri,
)

/**
 * Bounds legacy persisted values at the database boundary before constructing
 * the stricter public Locator contract. New writes always use validated Locator.
 */
fun ProgressEntity.toLocator(): Locator = Locator(
    chapter = chapter.coerceAtLeast(0),
    offset = offset.coerceAtLeast(0),
    progression = progression.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
)

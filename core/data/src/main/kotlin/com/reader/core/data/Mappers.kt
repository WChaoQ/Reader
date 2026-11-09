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

fun ProgressEntity.toLocator(): Locator = Locator(
    chapter = chapter,
    offset = offset,
    progression = progression,
)

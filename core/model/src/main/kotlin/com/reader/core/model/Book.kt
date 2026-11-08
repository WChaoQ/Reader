package com.reader.core.model

enum class BookFormat {
    EPUB, PDF, MD, TXT
}

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val fileSize: Long,
    val coverPath: String?,
    val importedAt: Long,
    val lastReadAt: Long?,
)

/** Unified locator. Reflow formats use chapter + offset; PDF uses chapter=page, offset=0 */
data class Locator(
    val chapter: Int,
    val offset: Int,
    val progression: Float = 0f,
)

data class BookMetadata(
    val title: String,
    val author: String? = null,
    val language: String? = null,
)

data class TocEntry(
    val title: String,
    val locator: Locator,
    val children: List<TocEntry> = emptyList(),
)

data class ResourceRef(val href: String)

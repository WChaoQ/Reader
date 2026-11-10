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
    val sourceUri: String? = null,
)

/**
 * Stable format-neutral reading position. Reflow uses zero-based chapter + UTF-16
 * text offset; fixed-layout formats use zero-based page in [chapter] and offset 0.
 * [progression] is an optional normalized fallback in the inclusive range 0..1;
 * it must never replace chapter/offset as the primary identity.
 */
data class Locator(
    val chapter: Int,
    val offset: Int,
    val progression: Float = 0f,
) {
    init {
        require(chapter >= 0) { "Locator chapter/page index must be zero-based and non-negative" }
        require(offset >= 0) { "Locator offset must be non-negative" }
        require(progression.isFinite() && progression in 0f..1f) {
            "Locator progression must be finite and normalized to 0..1"
        }
    }
}

data class BookMetadata(
    val title: String,
    val author: String? = null,
    val language: String? = null,
    /** Document-relative resource path for the cover image, if available. */
    val coverHref: String? = null,
)

data class TocEntry(
    val title: String,
    val locator: Locator,
    val children: List<TocEntry> = emptyList(),
)

data class ResourceRef(val href: String)

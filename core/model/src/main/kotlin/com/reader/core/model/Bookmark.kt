package com.reader.core.model

/** Database-independent bookmark exposed to UI and other application layers. */
data class Bookmark(
    val id: String,
    val bookId: String,
    val locator: Locator,
    val snippet: String,
    val createdAt: Long,
)

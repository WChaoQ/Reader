package com.reader.engine.layout

import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.engine.format.BookSource
import kotlinx.coroutines.flow.Flow

interface Paginator {
    /**
     * @param bookId when non-null, chapter page slices are read/written under
     *   pages/<bookId>/<layoutHash>/ for reuse across sessions.
     */
    fun paginate(
        book: BookSource,
        config: LayoutConfig,
        start: Locator,
        bookId: String? = null,
    ): Flow<PaginationEvent>
}

sealed interface PaginationEvent {
    data class ChapterReady(val chapter: Chapter, val pages: List<PageSlice>) : PaginationEvent
    data class Progress(val doneChapters: Int, val total: Int) : PaginationEvent
    data object Completed : PaginationEvent
}

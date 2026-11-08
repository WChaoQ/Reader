package com.reader.engine.layout

import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.engine.format.BookSource
import kotlinx.coroutines.flow.Flow

interface Paginator {
    fun paginate(
        book: BookSource,
        config: LayoutConfig,
        start: Locator,
    ): Flow<PaginationEvent>
}

sealed interface PaginationEvent {
    data class ChapterReady(val chapter: Chapter, val pages: List<PageSlice>) : PaginationEvent
    data class Progress(val doneChapters: Int, val total: Int) : PaginationEvent
    data object Completed : PaginationEvent
}

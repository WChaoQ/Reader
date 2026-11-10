package com.reader.engine.render

import android.graphics.Canvas
import com.reader.core.model.Locator
import kotlinx.coroutines.flow.StateFlow

data class PageCountInfo(
    val exact: Int? = null,
    val estimated: Int = 0,
    /** Number of pages that are currently addressable by the engine. */
    val available: Int = exact ?: estimated,
) {
    val display: Int get() = exact ?: estimated
    val isExact: Boolean get() = exact != null

    init {
        require(exact == null || exact >= 0) { "Exact page count must be non-negative" }
        require(estimated >= 0) { "Estimated page count must be non-negative" }
        require(available >= 0) { "Available page count must be non-negative" }
        require(exact == null || available <= exact) { "Available pages cannot exceed exact page count" }
    }
}

enum class ReaderCapability {
    PAGE_TEXT,
    FULL_TEXT_SEARCH,
    TEXT_SELECTION,
    LINK_ACTIVATION,
}

sealed interface HitResult {
    data class TextOffset(val chapter: Int, val offset: Int) : HitResult
    data class Link(val href: String) : HitResult
}

interface ReaderEngine {
    val pageCount: StateFlow<PageCountInfo>
    /** Explicitly advertises optional operations; callers must not infer support from empty results. */
    val capabilities: Set<ReaderCapability>
    val ready: StateFlow<Boolean>

    fun locatorOf(pageIndex: Int): Locator
    fun pageIndexOf(locator: Locator): Int

    fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int)

    fun pageText(pageIndex: Int): String

    /** Wait until a page is ready to present; reflow engines are ready synchronously. */
    suspend fun awaitPage(pageIndex: Int): Boolean = true

    fun close()

    /** Wait until in-flight backend work has stopped and owned resources are released. */
    suspend fun closeAndJoin() {
        close()
    }
}

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
        require(exact != null || estimated >= available) {
            "Estimated page count cannot be lower than available pages"
        }
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

/**
 * Format-neutral rendering facade consumed by the Reader feature.
 *
 * Implementations must keep drawing non-blocking: drawPage may draw cached output
 * and schedule work, but must not perform document I/O or wait for rendering.
 * close is idempotent; closeAndJoin waits for all backend work and owned resources.
 */
interface ReaderEngine {
    val pageCount: StateFlow<PageCountInfo>
    /** Explicitly advertises optional operations; callers must not infer support from empty results. */
    val capabilities: Set<ReaderCapability>
    val ready: StateFlow<Boolean>

    /** Resolves a global page index to the stable document position. */
    fun locatorOf(pageIndex: Int): Locator

    /** Resolves a stable document position to the currently available page index. */
    fun pageIndexOf(locator: Locator): Int

    /** Draws only already available output; must not block on I/O or rendering. */
    fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int)

    /** Available only when [ReaderCapability.PAGE_TEXT] is advertised. */
    fun pageText(pageIndex: Int): String

    /** Wait until a page is ready to present; reflow engines are ready synchronously. */
    suspend fun awaitPage(pageIndex: Int): Boolean = true

    fun close()

    /** Wait until in-flight backend work has stopped and owned resources are released. */
    suspend fun closeAndJoin() {
        close()
    }
}

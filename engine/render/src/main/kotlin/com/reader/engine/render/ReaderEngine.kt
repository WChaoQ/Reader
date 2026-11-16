package com.reader.engine.render

import android.graphics.Canvas
import com.reader.core.model.Locator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

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

/** Stable, format-neutral failure categories exposed by the rendering facade. */
enum class ReaderEngineFailureKind {
    PERMISSION_REVOKED,
    SOURCE_UNAVAILABLE,
    INVALID_DOCUMENT,
    UNSUPPORTED_CAPABILITY,
    OUT_OF_MEMORY_RISK,
    INTERNAL_FAILURE,
}

data class ReaderEngineFailure(
    val kind: ReaderEngineFailureKind,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Reader engine failure message must not be blank" }
    }

    companion object {
        fun fromOutOfMemoryError(
            message: String = "文档过大或设备内存不足，请关闭其他应用后重试",
        ): ReaderEngineFailure = ReaderEngineFailure(
            ReaderEngineFailureKind.OUT_OF_MEMORY_RISK,
            message,
        )

        fun fromException(
            failure: Exception,
            fallbackMessage: String,
        ): ReaderEngineFailure {
            val (kind, message) = when (failure) {
                is SecurityException ->
                    ReaderEngineFailureKind.PERMISSION_REVOKED to
                        "无法访问原文件，请重新授权或重新选择文档"
                is java.io.FileNotFoundException ->
                    ReaderEngineFailureKind.SOURCE_UNAVAILABLE to
                        "原文件已移动或删除，请重新选择文档"
                is UnsupportedOperationException ->
                    ReaderEngineFailureKind.UNSUPPORTED_CAPABILITY to
                        (failure.message?.takeIf { it.isNotBlank() } ?: fallbackMessage)
                is IllegalArgumentException ->
                    ReaderEngineFailureKind.INVALID_DOCUMENT to
                        "文档无效或内容损坏，无法继续读取"
                else ->
                    ReaderEngineFailureKind.INTERNAL_FAILURE to fallbackMessage
            }
            return ReaderEngineFailure(kind, message)
        }
    }
}

private object ReaderEngineFailureDefaults {
    val none: StateFlow<ReaderEngineFailure?> =
        MutableStateFlow<ReaderEngineFailure?>(null).asStateFlow()
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
    /** A typed failure emitted when asynchronous opening, layout, or rendering fails. */
    val failure: StateFlow<ReaderEngineFailure?>
        get() = ReaderEngineFailureDefaults.none

    /**
     * Completes when the opening page is ready or a failure is available.
     * A failed open must never leave callers waiting indefinitely on [ready].
     */
    suspend fun awaitReadyOrFailure(): ReaderEngineFailure? =
        combine(ready, failure) { isReady, openFailure -> isReady to openFailure }
            .first { (isReady, openFailure) -> isReady || openFailure != null }
            .second

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

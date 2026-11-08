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
}

sealed interface HitResult {
    data class TextOffset(val chapter: Int, val offset: Int) : HitResult
    data class Link(val href: String) : HitResult
}

interface ReaderEngine {
    val pageCount: StateFlow<PageCountInfo>
    val ready: StateFlow<Boolean>

    fun locatorOf(pageIndex: Int): Locator
    fun pageIndexOf(locator: Locator): Int

    fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int)

    fun pageText(pageIndex: Int): String

    fun close()
}

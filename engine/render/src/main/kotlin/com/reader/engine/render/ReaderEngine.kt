package com.reader.engine.render

import com.reader.core.model.Locator
import kotlinx.coroutines.flow.StateFlow

interface ReaderEngine {
    val pageCount: StateFlow<Int>
    fun locatorOf(pageIndex: Int): Locator
    fun pageIndexOf(locator: Locator): Int
}

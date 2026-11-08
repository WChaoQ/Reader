package com.reader.engine.render

import android.text.StaticLayout
import com.reader.engine.layout.PageSlice

data class ChapterLayout(
    val chapterIndex: Int,
    val title: String?,
    val plainText: String,
    val layout: StaticLayout,
    val pages: List<PageSlice>,
)

package com.reader.engine.layout

data class LayoutConfig(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val textSizePx: Float,
    val lineSpacingMult: Float = 1.4f,
    val paragraphSpacingPx: Float = 12f,
    val justify: Boolean = true,
) {
    val hash: String
        get() = listOf(pageWidthPx, pageHeightPx, textSizePx, lineSpacingMult, paragraphSpacingPx, justify)
            .joinToString("_")
}

data class PageSlice(
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
)

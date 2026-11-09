package com.reader.engine.layout

data class LayoutConfig(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val textSizePx: Float,
    val lineSpacingMult: Float = 1.4f,
    val paragraphSpacingPx: Float = 12f,
    val justify: Boolean = true,
    val paddingHorizontalPx: Int = 48,
    val paddingVerticalPx: Int = 64,
) {
    /**
     * Stable fingerprint for disk page-cache directories. Bump [ALGORITHM_VERSION]
     * whenever cut heuristics or DocumentTextBuilder spacing change.
     */
    val hash: String
        get() = listOf(
            ALGORITHM_VERSION,
            pageWidthPx, pageHeightPx, textSizePx,
            lineSpacingMult, paragraphSpacingPx, justify,
            paddingHorizontalPx, paddingVerticalPx,
        ).joinToString("_")

    companion object {
        /** Page-cut + text-builder algorithm version. */
        const val ALGORITHM_VERSION = 2
    }
}

data class PageSlice(
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
)

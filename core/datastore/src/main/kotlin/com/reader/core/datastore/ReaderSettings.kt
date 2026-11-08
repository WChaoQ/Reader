package com.reader.core.datastore

data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineSpacingMult: Float = 1.4f,
    val paragraphSpacingPx: Float = 12f,
    val justify: Boolean = true,
    val theme: String = "day",
    val pageTurnMode: String = "slide",
    val keepScreenOn: Boolean = false,
)

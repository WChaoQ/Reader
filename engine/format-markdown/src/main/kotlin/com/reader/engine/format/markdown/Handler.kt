package com.reader.engine.format.markdown

import com.reader.core.model.BookFormat
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatHandler
import java.io.File
import javax.inject.Inject

class MarkdownFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.MD
    override fun canHandle(fileName: String, magic: ByteArray): Boolean =
        fileName.endsWith(".md", ignoreCase = true) || fileName.endsWith(".markdown", ignoreCase = true)
    override suspend fun open(file: File): BookSource = error("Not implemented yet")
}

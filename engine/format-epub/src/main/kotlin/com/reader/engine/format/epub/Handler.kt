package com.reader.engine.format.epub

import com.reader.core.model.BookFormat
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatHandler
import java.io.File
import javax.inject.Inject

class EpubFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.EPUB
    override fun canHandle(fileName: String, magic: ByteArray): Boolean =
        fileName.endsWith(".epub", ignoreCase = true)
    override suspend fun open(file: File): BookSource = error("Not implemented yet")
}

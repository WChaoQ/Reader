package com.reader.engine.format.txt

import com.reader.core.model.BookFormat
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatHandler
import java.io.File
import javax.inject.Inject

class TxtFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.TXT
    override fun canHandle(fileName: String, magic: ByteArray): Boolean =
        fileName.endsWith(".txt", ignoreCase = true)
    override suspend fun open(file: File): BookSource = error("Not implemented yet")
}

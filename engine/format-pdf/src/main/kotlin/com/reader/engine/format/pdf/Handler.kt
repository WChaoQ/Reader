package com.reader.engine.format.pdf

import com.reader.core.model.BookFormat
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatHandler
import java.io.File
import javax.inject.Inject

class PdfFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.PDF
    override fun canHandle(fileName: String, magic: ByteArray): Boolean =
        magic.size >= 4 && magic[0] == 0x25.toByte() && magic[1] == 0x50.toByte() &&
            magic[2] == 0x44.toByte() && magic[3] == 0x46.toByte()
    override suspend fun open(file: File): BookSource = error("Not implemented yet")
}

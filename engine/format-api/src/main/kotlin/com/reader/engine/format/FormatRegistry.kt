package com.reader.engine.format

import com.reader.core.model.BookFormat
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FormatRegistry @Inject constructor(
    private val handlers: Set<@JvmSuppressWildcards FormatHandler>,
) {
    fun detect(fileName: String, magic: ByteArray): FormatHandler? =
        handlers.firstOrNull { it.canHandle(fileName, magic) }

    fun handlerFor(format: BookFormat): FormatHandler? =
        handlers.firstOrNull { it.format == format }

    suspend fun open(file: File, fileName: String): BookSource {
        val magic = file.inputStream().use { it.readNBytes(16) }
        val handler = detect(fileName, magic)
            ?: error("Unsupported format: $fileName")
        return handler.open(file)
    }
}

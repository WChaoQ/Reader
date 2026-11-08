package com.reader.engine.format

import com.reader.core.model.BookFormat
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves document formats without exposing concrete parser implementations to callers.
 * There must be exactly one registered handler per format and at most one match per document.
 */
@Singleton
class FormatRegistry @Inject constructor(
    private val handlers: Set<@JvmSuppressWildcards FormatHandler>,
) {
    init {
        require(handlers.map { it.format }.distinct().size == handlers.size) {
            "Only one FormatHandler may be registered for each BookFormat"
        }
    }

    fun detect(fileName: String, magic: ByteArray): FormatHandler? {
        val matches = handlers.filter { it.canHandle(fileName, magic) }
        check(matches.size <= 1) { "Ambiguous format handlers for document: $fileName" }
        return matches.singleOrNull()
    }

    fun handlerFor(format: BookFormat): FormatHandler? =
        handlers.singleOrNull { it.format == format }

    suspend fun open(file: File, fileName: String): BookSource {
        val magic = file.inputStream().use { it.readNBytes(MAGIC_BYTES) }
        val handler = detect(fileName, magic) ?: throw UnsupportedDocumentFormatException(fileName)
        val source = handler.open(file)
        check(source.format == handler.format) {
            "Handler for ${handler.format} returned a source for ${source.format}"
        }
        return source
    }

    private companion object {
        const val MAGIC_BYTES = 16
    }
}

class UnsupportedDocumentFormatException(fileName: String) :
    IllegalArgumentException("Unsupported document format: $fileName")

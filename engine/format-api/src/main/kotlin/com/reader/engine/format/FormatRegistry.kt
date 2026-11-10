package com.reader.engine.format

import com.reader.core.model.BookFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FormatRegistry @Inject constructor(
    private val handlers: Set<@JvmSuppressWildcards FormatHandler>,
) {
    init {
        require(handlers.map { it.format }.distinct().size == handlers.size) {
            "Only one FormatHandler may be registered for each BookFormat"
        }
    }

    fun detect(fileName: String, mimeType: String?, magic: ByteArray): FormatHandler? {
        val matches = handlers.filter { it.canHandle(fileName, mimeType, magic) }
        check(matches.size <= 1) { "Ambiguous format handlers for document: $fileName" }
        return matches.singleOrNull()
    }

    fun handlerFor(format: BookFormat): FormatHandler? =
        handlers.singleOrNull { it.format == format }

    suspend fun open(document: DocumentHandle, expectedFormat: BookFormat? = null): BookSource {
        val magic = document.openInputStream().use { it.readNBytes(MAGIC_BYTES) }
        require(magic.isNotEmpty()) { "Selected document is empty" }
        val handler = expectedFormat?.let(::handlerFor)
            ?: detect(document.displayName, document.mimeType, magic)
            ?: throw UnsupportedDocumentFormatException(document.displayName)

        var acquired: BookSource? = null
        return try {
            val source = handler.open(document)
            acquired = source
            currentCoroutineContext().ensureActive()
            if (source.format != handler.format) {
                acquired = null
                source.close()
                error("Handler for ${handler.format} returned a source for ${source.format}")
            }
            source
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }
            throw failure
        }
    }

    private companion object { const val MAGIC_BYTES = 16 }
}

class UnsupportedDocumentFormatException(fileName: String) :
    IllegalArgumentException("Unsupported document format: $fileName")

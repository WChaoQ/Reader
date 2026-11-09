package com.reader.engine.format.pdf

import com.reader.core.model.BookFormat
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.reader.engine.format.DocumentHandle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.PDF

    override fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean {
        if (fileName.endsWith(".pdf", ignoreCase = true) || mimeType == "application/pdf") return true
        return magic.size >= 4 &&
            magic[0] == 0x25.toByte() && magic[1] == 0x50.toByte() &&
            magic[2] == 0x44.toByte() && magic[3] == 0x46.toByte()
    }

    override suspend fun open(document: DocumentHandle): BookSource = PdfBookSource.open(document)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfFormatModule {
    @Binds
    @IntoSet
    abstract fun bindPdfHandler(impl: PdfFormatHandler): FormatHandler
}

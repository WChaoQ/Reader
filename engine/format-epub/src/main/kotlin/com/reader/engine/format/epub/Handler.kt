package com.reader.engine.format.epub

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
class EpubFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.EPUB

    override fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean =
        fileName.endsWith(".epub", ignoreCase = true) ||
            mimeType == "application/epub+zip" ||
            (mimeType == "application/zip" && magic.size >= 2 &&
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte())

    override suspend fun open(document: DocumentHandle): BookSource = EpubBookSource.open(document)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EpubFormatModule {
    @Binds
    @IntoSet
    abstract fun bindEpubHandler(impl: EpubFormatHandler): FormatHandler
}

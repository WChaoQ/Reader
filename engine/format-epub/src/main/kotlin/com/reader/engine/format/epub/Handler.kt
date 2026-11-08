package com.reader.engine.format.epub

import com.reader.core.model.BookFormat
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.EPUB

    override fun canHandle(fileName: String, magic: ByteArray): Boolean =
        fileName.endsWith(".epub", ignoreCase = true)

    override suspend fun open(file: File): BookSource = EpubBookSource.open(file)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EpubFormatModule {
    @Binds
    @IntoSet
    abstract fun bindEpubHandler(impl: EpubFormatHandler): FormatHandler
}

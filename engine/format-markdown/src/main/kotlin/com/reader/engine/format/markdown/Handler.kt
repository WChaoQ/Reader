package com.reader.engine.format.markdown

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
class MarkdownFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.MD

    override fun canHandle(fileName: String, magic: ByteArray): Boolean =
        fileName.endsWith(".md", ignoreCase = true) ||
            fileName.endsWith(".markdown", ignoreCase = true)

    override suspend fun open(file: File): BookSource = MarkdownBookSource.open(file)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MarkdownFormatModule {
    @Binds
    @IntoSet
    abstract fun bindMarkdownHandler(impl: MarkdownFormatHandler): FormatHandler
}

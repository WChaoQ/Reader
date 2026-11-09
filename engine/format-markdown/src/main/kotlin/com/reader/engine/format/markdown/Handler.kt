package com.reader.engine.format.markdown

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
class MarkdownFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.MD

    override fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean =
        fileName.endsWith(".md", ignoreCase = true) ||
            fileName.endsWith(".markdown", ignoreCase = true) || mimeType == "text/markdown" || mimeType == "text/x-markdown"

    override suspend fun open(document: DocumentHandle): BookSource = MarkdownBookSource.open(document)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MarkdownFormatModule {
    @Binds
    @IntoSet
    abstract fun bindMarkdownHandler(impl: MarkdownFormatHandler): FormatHandler
}

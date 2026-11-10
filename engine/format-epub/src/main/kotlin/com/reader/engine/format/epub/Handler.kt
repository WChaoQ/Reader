package com.reader.engine.format.epub

import android.content.Context
import com.reader.core.model.BookFormat
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import com.reader.engine.format.FormatHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubFormatHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) : FormatHandler {
    override val format: BookFormat = BookFormat.EPUB

    override fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean =
        fileName.endsWith(".epub", ignoreCase = true) ||
            mimeType.equals("application/epub+zip", ignoreCase = true)

    override suspend fun open(document: DocumentHandle): BookSource =
        ReadiumEpubBookSource.open(context, document)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EpubFormatModule {
    @Binds
    @IntoSet
    abstract fun bindEpubHandler(impl: EpubFormatHandler): FormatHandler
}

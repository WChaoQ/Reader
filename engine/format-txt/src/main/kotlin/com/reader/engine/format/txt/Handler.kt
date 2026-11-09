package com.reader.engine.format.txt

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
class TxtFormatHandler @Inject constructor() : FormatHandler {
    override val format: BookFormat = BookFormat.TXT

    override fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean {
        if (fileName.endsWith(".txt", ignoreCase = true)) return true
        if (mimeType == "text/plain" &&
            !fileName.endsWith(".md", ignoreCase = true) &&
            !fileName.endsWith(".markdown", ignoreCase = true)
        ) return true
        val isPdf = magic.size >= 4 &&
            magic[0] == 0x25.toByte() && magic[1] == 0x50.toByte() &&
            magic[2] == 0x44.toByte() && magic[3] == 0x46.toByte()
        val isZip = magic.size >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        return !isPdf && !isZip && fileName.substringAfterLast('.', "").isEmpty()
    }

    override suspend fun open(document: DocumentHandle): BookSource = TxtBookSource.open(document)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TxtFormatModule {
    @Binds
    @IntoSet
    abstract fun bindTxtHandler(impl: TxtFormatHandler): FormatHandler
}

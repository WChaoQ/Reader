package com.reader.engine.format

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.InputStream

class FormatRegistryTest {
    @Test
    fun detectReturnsMatchingHandler() {
        val handler = FakeHandler(BookFormat.TXT, ".txt")
        val registry = FormatRegistry(setOf(handler))

        assertEquals(handler, registry.detect("notes.txt", byteArrayOf()))
        assertNull(registry.detect("notes.pdf", byteArrayOf()))
    }

    @Test
    fun detectRejectsAmbiguousHandlersInsteadOfDependingOnSetOrder() {
        val registry = FormatRegistry(
            setOf(
                FakeHandler(BookFormat.TXT, ".doc"),
                FakeHandler(BookFormat.MD, ".doc"),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            registry.detect("sample.doc", byteArrayOf())
        }
    }

    @Test
    fun constructorRejectsDuplicateFormatRegistrations() {
        assertThrows(IllegalArgumentException::class.java) {
            FormatRegistry(
                setOf(
                    FakeHandler(BookFormat.TXT, ".txt"),
                    FakeHandler(BookFormat.TXT, ".text"),
                ),
            )
        }
    }

    @Test
    fun handlerLookupUsesFormat() {
        val txt = FakeHandler(BookFormat.TXT, ".txt")
        val registry = FormatRegistry(setOf(txt))

        assertEquals(txt, registry.handlerFor(BookFormat.TXT))
        assertNull(registry.handlerFor(BookFormat.PDF))
    }

    private class FakeHandler(
        override val format: BookFormat,
        private val extension: String,
    ) : FormatHandler {
        override fun canHandle(fileName: String, magic: ByteArray): Boolean =
            fileName.endsWith(extension, ignoreCase = true)

        override suspend fun open(file: File): BookSource = FakeSource(format)
    }

    private class FakeSource(
        override val format: BookFormat,
    ) : BookSource {
        override val chapterCount: Int = 0
        override suspend fun metadata() = BookMetadata("test")
        override suspend fun toc(): List<TocEntry> = emptyList()
        override suspend fun loadChapter(index: Int): Chapter =
            error("No chapters in test source")
        override suspend fun openResource(ref: ResourceRef): InputStream =
            error("No resources in test source")
        override fun close() = Unit
    }
}

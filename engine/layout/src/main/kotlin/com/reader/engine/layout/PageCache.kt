package com.reader.engine.layout

import com.reader.core.storage.BookFileStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binary on-disk cache for per-chapter [PageSlice] lists, keyed by bookId + layoutHash.
 * Format: magic "RPAG" + version(int) + count(int) + count × (startLine, endLine, startOffset, endOffset).
 */
@Singleton
class PageCache @Inject constructor(
    private val fileStore: BookFileStore,
) {
    fun load(bookId: String, layoutHash: String, chapterIndex: Int): List<PageSlice>? {
        val file = chapterFile(bookId, layoutHash, chapterIndex)
        if (!file.isFile || file.length() < 12) return null
        return runCatching {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return null
                val version = input.readInt()
                if (version != VERSION) return null
                val count = input.readInt()
                if (count <= 0 || count > 100_000) return null
                if (file.length() != 12L + count.toLong() * 16L) return null
                List(count) {
                    PageSlice(
                        startLine = input.readInt(),
                        endLine = input.readInt(),
                        startOffset = input.readInt(),
                        endOffset = input.readInt(),
                    )
                }
            }
        }.getOrNull()
    }

    fun save(bookId: String, layoutHash: String, chapterIndex: Int, pages: List<PageSlice>) {
        val file = chapterFile(bookId, layoutHash, chapterIndex)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            DataOutputStream(FileOutputStream(tmp).buffered()).use { out ->
                out.write(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(pages.size)
                pages.forEach { slice ->
                    out.writeInt(slice.startLine)
                    out.writeInt(slice.endLine)
                    out.writeInt(slice.startOffset)
                    out.writeInt(slice.endOffset)
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun chapterFile(bookId: String, layoutHash: String, chapterIndex: Int): File =
        File(fileStore.pagesCacheDir(bookId, layoutHash), "ch_$chapterIndex.bin")

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte())
        private const val VERSION = 1
    }
}

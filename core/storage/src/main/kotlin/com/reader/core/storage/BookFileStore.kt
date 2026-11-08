package com.reader.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val booksRoot: File
        get() = File(context.filesDir, "books").also { it.mkdirs() }

    fun bookDir(bookId: String): File =
        File(booksRoot, bookId).also { it.mkdirs() }

    fun originalFile(bookId: String, ext: String): File =
        File(bookDir(bookId), "original.$ext")

    fun coverFile(bookId: String): File =
        File(bookDir(bookId), "cover.webp")

    fun chaptersIndexFile(bookId: String): File =
        File(bookDir(bookId), "chapters.idx")

    fun tocFile(bookId: String): File =
        File(bookDir(bookId), "toc.json")

    fun pagesCacheDir(bookId: String, layoutHash: String): File =
        File(context.cacheDir, "pages/$bookId/$layoutHash").also { it.mkdirs() }

    fun thumbsCacheDir(bookId: String): File =
        File(context.cacheDir, "thumbs/$bookId").also { it.mkdirs() }

    fun copyAndHash(input: InputStream, suggestedName: String): Pair<File, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val temp = File(context.cacheDir, "import_${System.nanoTime()}_$suggestedName")
        temp.outputStream().use { out ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        val fullHex = digest.digest().joinToString("") { b -> "%02x".format(b) }
        val bookId = fullHex.take(32)
        return temp to bookId
    }

    fun commitOriginal(temp: File, bookId: String, ext: String): File {
        val dest = originalFile(bookId, ext)
        if (dest.exists()) {
            temp.delete()
            return dest
        }
        bookDir(bookId)
        if (!temp.renameTo(dest)) {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }
        return dest
    }

    fun deleteBook(bookId: String) {
        bookDir(bookId).deleteRecursively()
        File(context.cacheDir, "pages/$bookId").deleteRecursively()
        File(context.cacheDir, "thumbs/$bookId").deleteRecursively()
    }

    fun exists(bookId: String): Boolean =
        originalFile(bookId, "txt").exists() ||
            originalFile(bookId, "md").exists() ||
            originalFile(bookId, "epub").exists() ||
            originalFile(bookId, "pdf").exists()
}

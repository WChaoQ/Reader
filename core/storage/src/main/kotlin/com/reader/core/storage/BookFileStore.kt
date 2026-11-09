package com.reader.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
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

    /** Copies a provider stream while calculating a stable content identity. */
    fun copyAndHash(input: InputStream): Pair<File, String> {
        val temp = File.createTempFile("reader-import-", ".tmp", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            require(temp.length() > 0L) { "The selected document is empty" }
            val id = digest.digest().joinToString("") { byte -> "%02x".format(byte) }.take(32)
            return temp to id
        } catch (throwable: Throwable) {
            temp.delete()
            throw throwable
        }
    }

    /** Atomically stages a validated document in app-private storage. */
    fun commitOriginal(temp: File, bookId: String, ext: String): File {
        require(ext in SUPPORTED_EXTENSIONS) { "Unsupported file extension" }
        val directory = bookDir(bookId)
        val destination = File(directory, "original.$ext")
        if (destination.isFile) {
            temp.delete()
            return destination
        }

        val staged = File(directory, "original-${UUID.randomUUID()}.$ext.tmp")
        try {
            if (!temp.renameTo(staged)) {
                temp.copyTo(staged, overwrite = true)
                temp.delete()
            }
            check(staged.renameTo(destination)) { "Unable to finalize imported document" }
            return destination
        } catch (throwable: Throwable) {
            staged.delete()
            throw throwable
        }
    }

    fun deleteBook(bookId: String) {
        File(booksRoot, bookId).deleteRecursively()
        File(context.cacheDir, "pages/$bookId").deleteRecursively()
        File(context.cacheDir, "thumbs/$bookId").deleteRecursively()
    }

    fun exists(bookId: String): Boolean =
        SUPPORTED_EXTENSIONS.any { originalFile(bookId, it).isFile }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("txt", "md", "epub", "pdf")
    }
}

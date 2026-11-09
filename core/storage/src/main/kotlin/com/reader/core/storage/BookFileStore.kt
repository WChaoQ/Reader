package com.reader.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores only disposable reader artifacts. Original documents remain with their providers.
 * originalFile() exists solely for backward compatibility with books created by older versions.
 */
@Singleton
class BookFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val booksRoot: File
        get() = File(context.filesDir, "books")

    fun bookDir(bookId: String): File = File(booksRoot, bookId)

    /** Legacy source path; new documents are represented by persisted content:// URIs. */
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

    fun deleteBook(bookId: String) {
        File(booksRoot, bookId).deleteRecursively()
        File(context.cacheDir, "pages/$bookId").deleteRecursively()
        File(context.cacheDir, "thumbs/$bookId").deleteRecursively()
    }
}

package com.reader.core.storage

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookFileStore @Inject constructor() {
    fun bookDir(filesDir: File, bookId: String): File =
        File(filesDir, "books/$bookId").also { it.mkdirs() }

    fun originalFile(filesDir: File, bookId: String, ext: String): File =
        File(bookDir(filesDir, bookId), "original.$ext")
}

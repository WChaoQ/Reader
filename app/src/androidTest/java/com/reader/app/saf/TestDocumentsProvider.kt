package com.reader.app.saf

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A real Android DocumentsProvider hosted by the instrumentation APK.
 *
 * The fixture remains in the test APK's app-specific external cache. The provider
 * exposes it read-only and never copies it into the target application's storage.
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply {
            newRow().apply {
                columns.forEach { column ->
                    add(column, when (column) {
                        DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                        DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_LOCAL_ONLY
                        DocumentsContract.Root.COLUMN_ICON -> 0
                        DocumentsContract.Root.COLUMN_TITLE -> "Reader SAF Test Provider"
                        DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_DOCUMENT_ID
                        DocumentsContract.Root.COLUMN_MIME_TYPES -> MIME_TYPE
                        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> fixtureFile(requireNotNull(context)).parentFile?.usableSpace ?: 0L
                        else -> null
                    })
                }
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (documentId != DOCUMENT_ID) throw FileNotFoundException("Unknown test document: $documentId")
        return documentCursor(projection)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (parentDocumentId != ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("Unknown test directory: $parentDocumentId")
        }
        return documentCursor(projection)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (documentId != DOCUMENT_ID) throw FileNotFoundException("Unknown test document: $documentId")
        if (mode != "r") throw SecurityException("The SAF test document is read-only")
        signal?.throwIfCanceled()
        val file = fixtureFile(requireNotNull(context))
        if (!file.isFile) throw FileNotFoundException("SAF fixture does not exist")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_DOCUMENT_ID && documentId == DOCUMENT_ID

    private fun documentCursor(projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DOCUMENT_COLUMNS
        val file = fixtureFile(requireNotNull(context))
        if (!file.isFile) throw FileNotFoundException("SAF fixture does not exist")
        return MatrixCursor(columns).apply {
            newRow().apply {
                columns.forEach { column ->
                    add(column, when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> DOCUMENT_ID
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> DISPLAY_NAME
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> MIME_TYPE
                        DocumentsContract.Document.COLUMN_SIZE -> file.length()
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        else -> null
                    })
                }
            }
        }
    }

    companion object {
        const val AUTHORITY = "com.reader.app.test.documents"
        const val ROOT_ID = "reader-test"
        const val ROOT_DOCUMENT_ID = "root"
        const val DOCUMENT_ID = "fixture.epub"
        const val DISPLAY_NAME = "fixture.epub"
        const val MIME_TYPE = "application/epub+zip"

        fun fixtureFile(context: Context): File {
            val cacheDirectory = requireNotNull(context.externalCacheDir) {
                "SAF instrumentation fixture requires app-specific external cache"
            }
            return File(cacheDirectory, "reader-saf/fixture.epub")
        }

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}

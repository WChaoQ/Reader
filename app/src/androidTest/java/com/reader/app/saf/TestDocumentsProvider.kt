package com.reader.app.saf

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A real Android DocumentsProvider hosted by the instrumentation APK.
 *
 * The synthetic EPUB is created by the provider process in its own internal cache.
 * The provider exposes it read-only; user-selected documents are never copied here.
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        createFixture(fixtureFile(requireNotNull(context)))
        return true
    }

    /**
     * Issues fixture grants from the provider's own UID. The instrumentation code
     * runs as the target app and cannot delegate a URI it does not own.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callerPackage = getCallingPackage()
            ?: throw SecurityException("Caller package is required")
        if (callerPackage != TARGET_PACKAGE) {
            throw SecurityException("Only the Reader app may control the SAF test grant")
        }

        val providerContext = requireNotNull(context)
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, DOCUMENT_ID)
        val identity = Binder.clearCallingIdentity()
        try {
            when (method) {
                METHOD_GRANT_READ -> providerContext.grantUriPermission(
                    callerPackage,
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                METHOD_REVOKE_READ -> providerContext.revokeUriPermission(
                    callerPackage,
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                else -> return super.call(method, arg, extras)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        return Bundle().apply { putBoolean(KEY_SUCCESS, true) }
    }

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
        const val TARGET_PACKAGE = "com.reader.app"
        const val METHOD_GRANT_READ = "reader.test.GRANT_READ"
        const val METHOD_REVOKE_READ = "reader.test.REVOKE_READ"
        const val KEY_SUCCESS = "success"

        private fun fixtureFile(context: Context): File =
            File(context.cacheDir, "reader-saf/fixture.epub")

        private fun createFixture(file: File) {
            val directory = requireNotNull(file.parentFile)
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create SAF test fixture directory"
            }
            ZipOutputStream(file.outputStream()).use { zip ->
                val mimetype = "application/epub+zip".toByteArray()
                val crc = CRC32().apply { update(mimetype) }
                zip.putNextEntry(
                    ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = mimetype.size.toLong()
                        compressedSize = mimetype.size.toLong()
                        this.crc = crc.value
                    },
                )
                zip.write(mimetype)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write(
                    """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                        .toByteArray(),
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zip.write(
                    """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">saf-fixture</dc:identifier><dc:title>SAF Test Book</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>"""
                        .toByteArray(),
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("OEBPS/chapter.xhtml"))
                zip.write(
                    """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter One</title></head><body><p>Persisted SAF chapter content for the reader integration test.</p></body></html>"""
                        .toByteArray(),
                )
                zip.closeEntry()
            }
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

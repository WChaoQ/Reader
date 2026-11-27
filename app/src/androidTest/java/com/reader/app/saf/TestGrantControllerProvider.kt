package com.reader.app.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.DocumentsContract

/**
 * Test-only IPC endpoint for controlling the SAF fixture grant.
 *
 * This provider intentionally does not require MANAGE_DOCUMENTS: that permission
 * belongs only on TestDocumentsProvider so ordinary app code cannot bypass
 * DocumentsUI. Both providers are installed in the instrumentation APK and share
 * its UID, so this endpoint can issue a narrow grant for the document authority.
 */
class TestGrantControllerProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callerPackage = getCallingPackage()
            ?: throw SecurityException("Caller package is required")
        if (callerPackage != TARGET_PACKAGE) {
            throw SecurityException("Only the Reader app may control the SAF test grant")
        }

        val providerContext = requireNotNull(context)
        val documentUri = DocumentsContract.buildDocumentUri(
            DOCUMENT_AUTHORITY,
            DOCUMENT_ID,
        )
        val identity = Binder.clearCallingIdentity()
        try {
            when (method) {
                METHOD_GRANT_READ -> providerContext.grantUriPermission(
                    TARGET_PACKAGE,
                    documentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                METHOD_REVOKE_READ -> providerContext.revokeUriPermission(
                    TARGET_PACKAGE,
                    documentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                else -> return super.call(method, arg, extras)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        return Bundle().apply { putBoolean(KEY_SUCCESS, true) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("The SAF grant controller is not writable")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("The SAF grant controller is not writable")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("The SAF grant controller is not writable")

    companion object {
        const val AUTHORITY = "com.reader.app.test.grants"
        const val DOCUMENT_AUTHORITY = TestDocumentsProvider.AUTHORITY
        const val DOCUMENT_ID = TestDocumentsProvider.DOCUMENT_ID
        const val TARGET_PACKAGE = "com.reader.app"
        const val METHOD_GRANT_READ = "reader.test.GRANT_READ"
        const val METHOD_REVOKE_READ = "reader.test.REVOKE_READ"
        const val KEY_SUCCESS = "success"
    }
}

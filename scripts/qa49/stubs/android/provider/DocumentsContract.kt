package android.provider

import android.content.ContentResolver
import android.net.Uri

/** Host-only signature mirror of the documents constants the production unit reads. */
object DocumentsContract {
    fun getTreeDocumentId(uri: Uri): String {
        val after = uri.rawText.substringAfter("tree=", "")
        return after.substringBefore('?')
    }

    fun buildDocumentUriUsingTree(uri: Uri, documentId: String): Uri =
        Uri(uri.scheme, uri.authority, "doc=$documentId")

    fun deleteDocument(resolver: ContentResolver, uri: Uri): Boolean = false

    object Document {
        const val COLUMN_MIME_TYPE: String = "mime_type"
        const val COLUMN_FLAGS: String = "flags"
        const val COLUMN_DOCUMENT_ID: String = "document_id"
        const val MIME_TYPE_DIR: String = "vnd.android.document/directory"
        const val FLAG_DIR_SUPPORTS_CREATE: Int = 4
    }
}

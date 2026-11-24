// SAF-06 synthetic DocumentsContract double. Document ids are scratch labels, never real paths.
package android.provider

import android.content.ContentResolver
import android.net.Uri

object DocumentsContract {
    @JvmStatic
    fun getTreeDocumentId(uri: Uri): String? = documentSegment(uri, "tree")

    @JvmStatic
    fun getDocumentId(uri: Uri): String? = documentSegment(uri, "document") ?: documentSegment(uri, "tree")

    @JvmStatic
    fun buildDocumentUriUsingTree(treeUri: Uri, documentId: String): Uri {
        val treeId = getTreeDocumentId(treeUri) ?: ""
        return Uri.parse(
            "content://${treeUri.authority}/tree/${encode(treeId)}/document/${encode(documentId)}",
        )
    }

    @JvmStatic
    fun deleteDocument(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.delete(uri, null, null) > 0

    private fun documentSegment(uri: Uri, name: String): String? {
        val path = uri.path ?: return null
        val marker = "/$name/"
        val start = path.indexOf(marker)
        if (start < 0) return null
        val value = path.substring(start + marker.length)
        val end = value.indexOf('/')
        return decode(if (end < 0) value else value.substring(0, end))
    }

    private fun encode(value: String): String = value.replace(":", "%3A").replace("/", "%2F")

    private fun decode(value: String): String =
        value.replace("%3A", ":", ignoreCase = true).replace("%2F", "/", ignoreCase = true)

    object Document {
        const val COLUMN_DOCUMENT_ID = "document_id"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_FLAGS = "flags"
        const val MIME_TYPE_DIR = "vnd.android.document/directory"
        const val FLAG_DIR_SUPPORTS_CREATE = 8
    }
}

package com.devtunasoft.tuneros

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

internal data class SafStorageInfo(
    val treeUri: Uri,
    val rootTitle: String?,
    val availableBytes: Long,
)

internal object SafStorageInspector {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun inspect(context: Context, treeUri: Uri): SafStorageInfo? = runCatching {
        if (treeUri.scheme != "content" || treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            return@runCatching null
        }

        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        if (treeDocumentId.isBlank()) return@runCatching null

        val selectedDocument = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            treeDocumentId,
        )
        val canCreate = resolver.query(
            selectedDocument,
            arrayOf(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                false
            } else {
                val mime = cursor.getString(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
                )
                val flags = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS),
                )
                mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L
            }
        } ?: false
        if (!canCreate) return@runCatching null

        val rootsUri = DocumentsContract.buildRootsUri(EXTERNAL_STORAGE_AUTHORITY)
        resolver.query(
            rootsUri,
            arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val documentIdColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            )
            val titleColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Root.COLUMN_TITLE,
            )
            val availableColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            )
            var bestDocumentIdLength = -1
            var bestTitle: String? = null
            var bestAvailable: Long? = null
            while (cursor.moveToNext()) {
                val rootDocumentId = cursor.getString(documentIdColumn) ?: continue
                if (!treeDocumentId.startsWith(rootDocumentId) ||
                    rootDocumentId.length <= bestDocumentIdLength
                ) {
                    continue
                }
                if (cursor.isNull(availableColumn)) continue
                val available = cursor.getLong(availableColumn)
                if (available < 0) continue
                bestDocumentIdLength = rootDocumentId.length
                bestTitle = if (cursor.isNull(titleColumn)) null else cursor.getString(titleColumn)
                bestAvailable = available
            }
            bestAvailable?.let { available ->
                SafStorageInfo(treeUri, bestTitle, available)
            }
        }
    }.getOrNull()
}

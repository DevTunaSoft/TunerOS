package com.devtunasoft.tuneros

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

internal data class SafStorageInfo(
    val treeUri: Uri,
    val rootTitle: String?,
    val availableBytes: Long,
)

internal object SafStorageInspector {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val TAG = "SafStorageInspector"

    fun inspect(context: Context, treeUri: Uri): SafStorageInfo? = runCatching {
        if (treeUri.scheme != "content" || treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            return@runCatching null
        }

        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        if (treeDocumentId.isBlank()) return@runCatching null

        // A stored tree URI is not proof of access: the grant may have been revoked, replaced or
        // retained read-only. Fail closed unless this exact tree still has a persisted read+write
        // grant, so inspection cannot advertise a destination that cannot be written to.
        if (!hasPersistedWriteGrant(context, treeUri, treeDocumentId)) {
            debugLog(context, "persistedWriteGrant=false")
            return@runCatching null
        }

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
        debugLog(context, "tree=$treeDocumentId canCreate=$canCreate")
        if (!canCreate) return@runCatching null

        // Free space comes from the storage volume that holds the folder. The provider's roots list cannot be
        // read by an app ("requires that you obtain access using ACTION_OPEN_DOCUMENT"), so it is not queried.
        val volumeId = treeDocumentId.substringBefore(':', missingDelimiterValue = "")
        val volume = volumeSpaces(context).firstOrNull { it.volumeId == volumeId }
        debugLog(context, "volume=$volumeId found=${volume != null} usable=${volume?.usableBytes}")
        if (volume == null || volume.usableBytes < 0L) return@runCatching null
        SafStorageInfo(treeUri, volumeId, volume.usableBytes)
    }.onFailure { failure ->
        debugLog(context, "inspect failed: ${failure.javaClass.simpleName}")
    }.getOrNull()

    private class VolumeSpace(val volumeId: String, val usableBytes: Long)

    /** True only for a persisted read+write grant covering this exact tree. */
    private fun hasPersistedWriteGrant(
        context: Context,
        treeUri: Uri,
        treeDocumentId: String,
    ): Boolean = context.contentResolver.persistedUriPermissions.any { permission ->
        if (permission.uri.authority != treeUri.authority ||
            !permission.isReadPermission || !permission.isWritePermission
        ) {
            return@any false
        }
        runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull() == treeDocumentId
    }

    /**
     * Usable space for each mounted storage volume. The app's own external files directory on each volume is
     * public API (minSdk 26) and needs no permission; the usable space of that path is the volume's free space.
     * The volume id is "primary" for shared internal storage and the card's UUID for SD or USB storage.
     */
    private fun volumeSpaces(context: Context): List<VolumeSpace> {
        val spaces = ArrayList<VolumeSpace>()
        for (dir in context.getExternalFilesDirs(null)) {
            if (dir == null) continue
            if (!dir.exists()) dir.mkdirs()
            val path = dir.absolutePath
            val volumeId = when {
                path.startsWith("/storage/emulated/") -> "primary"
                path.startsWith("/storage/") -> path.removePrefix("/storage/").substringBefore('/')
                else -> continue
            }
            if (volumeId.isEmpty()) continue
            spaces += VolumeSpace(volumeId, dir.usableSpace)
        }
        return spaces
    }

    /** Debug builds only. Logs document ids and sizes, never a URI. */
    private fun debugLog(context: Context, message: String) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.i(TAG, message)
        }
    }
}

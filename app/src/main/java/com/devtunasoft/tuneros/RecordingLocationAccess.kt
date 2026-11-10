package com.devtunasoft.tuneros

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

internal object RecordingLocationAccess {
    enum class DeleteResult { DELETED, MISSING, INVALID, UNAVAILABLE, FAILED }
    private enum class ContentState { EXISTS, MISSING, UNAVAILABLE }

    fun playableUri(context: Context, location: String?): Uri? {
        if (location.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(location) }.getOrNull() ?: return null
        return when (parsed.scheme?.lowercase()) {
            "content" -> parsed.takeIf {
                hasPersistedAccess(context, it, requireWrite = false) &&
                    contentDocumentState(context, it) == ContentState.EXISTS
            }
            "file" -> internalFile(context, parsed.path)?.let(Uri::fromFile)
            null -> internalFile(context, location)?.let(Uri::fromFile)
            else -> null
        }
    }

    fun delete(context: Context, location: String?): DeleteResult {
        if (location.isNullOrBlank()) return DeleteResult.MISSING
        val parsed = runCatching { Uri.parse(location) }.getOrNull()
            ?: return DeleteResult.INVALID
        return when (parsed.scheme?.lowercase()) {
            "content" -> deleteContent(context, parsed)
            "file" -> deleteInternal(context, parsed.path)
            null -> deleteInternal(context, location)
            else -> DeleteResult.INVALID
        }
    }

    private fun deleteInternal(context: Context, path: String?): DeleteResult {
        val file = internalFile(context, path, requireExists = false)
            ?: return DeleteResult.INVALID
        if (!file.exists()) return DeleteResult.MISSING
        return if (file.delete()) DeleteResult.DELETED else DeleteResult.FAILED
    }

    private fun deleteContent(context: Context, uri: Uri): DeleteResult {
        if (!hasPersistedAccess(context, uri, requireWrite = true)) {
            return DeleteResult.INVALID
        }
        when (contentDocumentState(context, uri)) {
            ContentState.MISSING -> return DeleteResult.MISSING
            ContentState.UNAVAILABLE -> return DeleteResult.UNAVAILABLE
            ContentState.EXISTS -> Unit
        }
        return if (runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
        ) {
            DeleteResult.DELETED
        } else {
            DeleteResult.FAILED
        }
    }

    private fun internalFile(
        context: Context,
        path: String?,
        requireExists: Boolean = true,
    ): File? {
        if (path.isNullOrBlank()) return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (requireExists && !candidate.isFile) return null
        val roots = listOfNotNull(
            context.getExternalFilesDir("recordings"),
            File(context.filesDir, "recordings"),
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        val inside = roots.any { root ->
            candidate.path.startsWith(root.path + File.separator)
        }
        return candidate.takeIf { inside }
    }

    private fun hasPersistedAccess(
        context: Context,
        documentUri: Uri,
        requireWrite: Boolean,
    ): Boolean {
        if (documentUri.authority != SafStorageInspector.EXTERNAL_STORAGE_AUTHORITY) return false
        val targetDocumentId = runCatching {
            DocumentsContract.getDocumentId(documentUri)
        }.getOrNull() ?: return false

        return context.contentResolver.persistedUriPermissions.any { permission ->
            if (permission.uri.authority != documentUri.authority ||
                !permission.isReadPermission ||
                (requireWrite && !permission.isWritePermission)
            ) {
                return@any false
            }
            val treeId = runCatching {
                DocumentsContract.getTreeDocumentId(permission.uri)
            }.getOrNull() ?: return@any false
            targetDocumentId == treeId ||
                if (treeId.endsWith(":")) {
                    targetDocumentId.startsWith(treeId)
                } else {
                    targetDocumentId.startsWith("$treeId/")
                }
        }
    }

    private fun contentDocumentState(context: Context, uri: Uri): ContentState {
        return try {
            val exists = context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: return ContentState.UNAVAILABLE
            if (exists) ContentState.EXISTS else ContentState.MISSING
        } catch (_: Exception) {
            ContentState.UNAVAILABLE
        }
    }
}

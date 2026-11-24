package com.devtunasoft.tuneros.saf06

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.database.Cursor
import android.database.Saf06Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Synthetic values only: every id, label and path below is a scratch fixture. */
internal object Saf06Fixture {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val FOLDER = "SyntheticFolder"
    const val OTHER_FOLDER = "OtherSyntheticFolder"
    const val DOC_NAME = "synthetic-recording.ts"
    const val USABLE_BYTES = 2L * 1024 * 1024 * 1024

    enum class DocState { EXISTS, MISSING, UNAVAILABLE }

    fun treeUri(folder: String = FOLDER): Uri =
        Uri.parse("content://$AUTHORITY/tree/primary%3A$folder")

    fun recordingUri(folder: String = FOLDER): Uri = Uri.parse(
        "content://$AUTHORITY/tree/primary%3A$folder/document/primary%3A$folder%2F$DOC_NAME",
    )

    fun documentUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)!!)

    fun permission(folder: String, read: Boolean = true, write: Boolean = true): UriPermission =
        UriPermission(treeUri(folder), read, write)

    fun writableCursor(): Cursor = Saf06Cursor(
        columns = mapOf(
            DocumentsContract.Document.COLUMN_MIME_TYPE to 0,
            DocumentsContract.Document.COLUMN_FLAGS to 1,
        ),
        values = mapOf(
            0 to DocumentsContract.Document.MIME_TYPE_DIR,
            1 to DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong(),
        ),
    )

    fun unwritableCursor(): Cursor = Saf06Cursor(
        columns = mapOf(
            DocumentsContract.Document.COLUMN_MIME_TYPE to 0,
            DocumentsContract.Document.COLUMN_FLAGS to 1,
        ),
        values = mapOf(0 to DocumentsContract.Document.MIME_TYPE_DIR, 1 to 0L),
    )

    /** A File double: volume mapping is driven by its path, with no real directory created. */
    class SyntheticVolumeDir(private val syntheticPath: String, private val syntheticUsable: Long) :
        File(syntheticPath) {
        override fun getAbsolutePath(): String = syntheticPath
        override fun getCanonicalPath(): String = syntheticPath
        override fun exists(): Boolean = true
        override fun mkdirs(): Boolean = true
        override fun getUsableSpace(): Long = syntheticUsable
    }

    fun context(
        persisted: List<UriPermission> = emptyList(),
        createAllowed: Boolean = true,
        usableBytes: Long = USABLE_BYTES,
        volumePath: String = "/storage/emulated/0/Android/data/com.devtunasoft.tuneros/files",
    ): Context = object : Context() {
        override val contentResolver: ContentResolver = object : ContentResolver() {
            override val persistedUriPermissions: List<UriPermission> = persisted

            override fun query(
                uri: Uri,
                projection: Array<String>?,
                selection: String?,
                selectionArgs: Array<String>?,
                sortOrder: String?,
            ): Cursor? = if (createAllowed) writableCursor() else unwritableCursor()
        }

        override fun getExternalFilesDirs(type: String?): Array<File?> =
            arrayOf(SyntheticVolumeDir(volumePath, usableBytes))
    }

    /** Resolver fixture for RecordingLocationAccess content-document checks. */
    fun contentContext(
        persisted: List<UriPermission> = emptyList(),
        docState: DocState = DocState.EXISTS,
        deleted: MutableList<Uri> = ArrayList(),
    ): Context = object : Context() {
        override val contentResolver: ContentResolver = object : ContentResolver() {
            override val persistedUriPermissions: List<UriPermission> = persisted

            override fun query(
                uri: Uri,
                projection: Array<String>?,
                selection: String?,
                selectionArgs: Array<String>?,
                sortOrder: String?,
            ): Cursor? = when (docState) {
                DocState.EXISTS -> Saf06Cursor(
                    mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to 0),
                    mapOf(0 to "primary:$FOLDER/$DOC_NAME"),
                )
                DocState.MISSING -> Saf06Cursor(emptyMap(), emptyMap(), hasRow = false)
                DocState.UNAVAILABLE -> throw SecurityException("synthetic provider disconnect")
            }

            override fun delete(uri: Uri, where: String?, selectionArgs: Array<String>?): Int {
                deleted += uri
                return 1
            }
        }
    }
}

package com.devtunasoft.tuneros

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.devtunasoft.tuneros.core.RecordingPolicy
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

internal interface RecordingSink {
    val location: String
    val bytesWritten: Long

    fun openOutput(): OutputStream
    fun freeBytes(): Long
    fun finalizeRecording(): String?
    fun delete(): Boolean
}

internal object RecordingSinkFactory {
    fun create(
        context: Context,
        safeId: String,
        extension: String,
    ): RecordingSink {
        val treeUri = RecordingDestinationStore(context).selectedTreeUri()
        if (treeUri == null) {
            val directory = (context.getExternalFilesDir("recordings")
                ?: File(context.filesDir, "recordings")).apply { mkdirs() }
            require(directory.isDirectory) { "Recording storage unavailable" }
            return InternalRecordingSink(directory, safeId, extension)
        }

        val info = SafStorageInspector.inspect(context, treeUri)
            ?: throw IllegalStateException(
                "Selected recording storage is unavailable or cannot report free space",
            )
        require(info.availableBytes >= RecordingPolicy.DEFAULT_MIN_FREE_BYTES) {
            "Selected recording storage is below the safety reserve"
        }
        return SafRecordingSink(context, info, safeId, extension)
    }
}

private class CountingOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {
    var count: Long = 0
        private set

    override fun write(oneByte: Int) {
        out.write(oneByte)
        count = Math.addExact(count, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        count = Math.addExact(count, length.toLong())
    }
}

private class InternalRecordingSink(
    private val directory: File,
    safeId: String,
    extension: String,
) : RecordingSink {
    private val partial = File(directory, "$safeId.partial")
    private val completed = File(directory, safeId + extension)
    private var current = partial
    private var ownsCurrent = false
    private var counter: CountingOutputStream? = null

    override val location: String get() = current.absolutePath
    override val bytesWritten: Long get() =
        counter?.count ?: current.takeIf { ownsCurrent && it.exists() }?.length() ?: 0L

    override fun openOutput(): OutputStream {
        check(counter == null) { "Recording output already opened" }
        check(!completed.exists()) { "Recording file already exists" }
        check(partial.createNewFile()) { "Partial recording file already exists" }
        ownsCurrent = true
        val output = try {
            CountingOutputStream(partial.outputStream())
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
        counter = output
        return output
    }

    override fun freeBytes(): Long = directory.usableSpace

    override fun finalizeRecording(): String? {
        // Never delete an older recording to make a colliding filename available.
        if (completed.exists()) return null
        if (!partial.renameTo(completed)) return null
        current = completed
        return current.absolutePath
    }

    override fun delete(): Boolean =
        ownsCurrent && (!current.exists() || current.delete())
}

private class SafRecordingSink(
    private val context: Context,
    private val info: SafStorageInfo,
    safeId: String,
    private val extension: String,
) : RecordingSink {
    private val resolver = context.contentResolver
    private val treeUri = info.treeUri
    private val finalDisplayName = safeId + extension
    private var documentUri: Uri = createDocument(safeId + ".partial" + extension)
    private var counter: CountingOutputStream? = null
    private var lastReportedAvailable = info.availableBytes
    private var writtenAtLastRefresh = 0L

    override val location: String get() = documentUri.toString()
    override val bytesWritten: Long get() = counter?.count ?: 0L

    override fun openOutput(): OutputStream {
        check(counter == null) { "Recording output already opened" }
        val delegate = resolver.openOutputStream(documentUri, "w")
            ?: throw IllegalStateException("Selected recording storage cannot open output")
        val output = CountingOutputStream(delegate)
        counter = output
        return output
    }

    override fun freeBytes(): Long {
        val written = bytesWritten
        if (written - writtenAtLastRefresh >= FREE_SPACE_REFRESH_BYTES) {
            SafStorageInspector.inspect(context, treeUri)?.let { refreshed ->
                lastReportedAvailable = refreshed.availableBytes
                writtenAtLastRefresh = written
            }
        }
        return (lastReportedAvailable - (written - writtenAtLastRefresh)).coerceAtLeast(0L)
    }

    override fun finalizeRecording(): String? {
        val renamed = runCatching {
            DocumentsContract.renameDocument(resolver, documentUri, finalDisplayName)
        }.getOrNull()
        if (renamed != null) {
            documentUri = renamed
            return documentUri.toString()
        }
        return documentUri.toString().takeIf { documentStillExists() }
    }

    private fun documentStillExists(): Boolean =
        runCatching {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)

    override fun delete(): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, documentUri)
    }.getOrDefault(false)

    private fun createDocument(displayName: String): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        return DocumentsContract.createDocument(
            resolver,
            parent,
            mimeTypeFor(extension),
            displayName,
        ) ?: throw IllegalStateException("Selected recording storage cannot create files")
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        ".ts", ".m2ts" -> "video/mp2t"
        ".mp4" -> "video/mp4"
        ".webm" -> "video/webm"
        ".mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    companion object {
        private const val FREE_SPACE_REFRESH_BYTES = 16L * 1024 * 1024
    }
}

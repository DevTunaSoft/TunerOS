package com.devtunasoft.tuneros

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.RecordingState
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

/** Terminal result requested by a capture engine, shared so finalization can be tested on the JVM. */
internal data class RecordingOutcome(
    val state: String,
    val bytesWritten: Long,
    val finalizeFile: Boolean,
    val failureReason: String? = null,
)

/** The single terminal transition a recorder is allowed to perform for one capture. */
internal data class RecordingFinalization(
    val state: String,
    val finalize: Boolean,
    val discardEmpty: Boolean,
    val failureReason: String?,
)

internal object RecordingOutcomePolicy {
    /**
     * Resolve a capture result against the live cancellation flag and the sink's authoritative byte count.
     * Cancellation (including service destruction) and an empty capture never produce COMPLETED and never
     * rename a partial file. Only bytes acknowledged by both sides can justify finalization.
     */
    fun settle(requested: RecordingOutcome, cancelled: Boolean, sinkBytes: Long): RecordingFinalization {
        require(requested.bytesWritten >= 0) { "Negative reported bytes" }
        require(sinkBytes >= 0) { "Negative sink bytes" }
        if (cancelled) return RecordingFinalization("CANCELLED", false, false, null)

        val bytes = minOf(requested.bytesWritten, sinkBytes)
        if (bytes == 0L) {
            val state = RecordingPolicy.finalState(RecordingState.valueOf(requested.state), 0L).name
            val transitioned = state != requested.state
            return RecordingFinalization(
                state = state,
                finalize = false,
                discardEmpty = state == "FAILED",
                failureReason = requested.failureReason
                    ?: if (transitioned) "No bytes before scheduled stop" else null,
            )
        }

        val state = RecordingPolicy.finalState(RecordingState.valueOf(requested.state), bytes).name
        if (state != "COMPLETED" || !requested.finalizeFile) {
            val settled = if (state == "COMPLETED") "PARTIAL" else state
            return RecordingFinalization(
                state = settled,
                finalize = false,
                discardEmpty = false,
                failureReason = requested.failureReason
                    ?: if (settled == "PARTIAL") "Output was not finalized" else null,
            )
        }
        return RecordingFinalization("COMPLETED", true, false, null)
    }
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

internal class InternalRecordingSink(
    private val directory: File,
    safeId: String,
    extension: String,
) : RecordingSink {
    private val partial = File(directory, "$safeId.partial")
    private val completed = File(directory, safeId + extension)
    private var current = partial
    private var ownsCurrent = false
    private var counter: CountingOutputStream? = null
    private var finalizedLocation: String? = null

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
        // Repeated finalization is a no-op: never rename twice or report a completed
        // recording as PARTIAL because a stale caller asked a second time.
        finalizedLocation?.let { return it }
        // An empty capture must never become a playable completed file.
        if (bytesWritten <= 0L) return null
        // Never delete an older recording to make a colliding filename available.
        if (completed.exists()) return null
        if (!partial.renameTo(completed)) return null
        current = completed
        finalizedLocation = current.absolutePath
        return finalizedLocation
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
    private var finalizedLocation: String? = null
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
            // If the removable drive disappears or stops reporting capacity,
            // never keep recording against an old, potentially wrong value.
            val refreshed = SafStorageInspector.inspect(context, treeUri) ?: return 0L
            lastReportedAvailable = refreshed.availableBytes
            writtenAtLastRefresh = written
        }
        return RecordingPolicy.remainingFreeBytes(
            lastReportedAvailable, written - writtenAtLastRefresh,
        )
    }

    override fun finalizeRecording(): String? {
        finalizedLocation?.let { return it }
        // No acknowledged bytes means no playable recording; leave the .partial document as-is.
        if (bytesWritten <= 0L) return null
        val renamed = runCatching {
            DocumentsContract.renameDocument(resolver, documentUri, finalDisplayName)
        }.getOrNull()
        // An existing .partial file is not a successfully finalized recording.
        // Let the catalog mark it PARTIAL rather than report false completion.
        if (renamed == null) return null
        documentUri = renamed
        finalizedLocation = documentUri.toString()
        return finalizedLocation
    }

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

package com.devtunasoft.tuneros

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.RecordingState
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

internal interface RecordingSink {
    val location: String
    val bytesWritten: Long

    fun openOutput(): OutputStream
    fun freeBytes(): Long
    fun finalizeRecording(): String?
    fun delete(): Boolean
}

/**
 * Typed storage/SAF fault. Deliberately payload-free: no URI, path, exception text or media byte
 * is retained, so a fault can be persisted or logged without leaking provider or stream details.
 */
internal enum class StorageFaultKind {
    PERMISSION_LOST,
    DISK_FULL,
    PROVIDER_DISCONNECTED,
    FINALIZATION_FAILED,
    CANCELLED,
    UNKNOWN,
}

internal data class StorageFault(val kind: StorageFaultKind) {
    /** Stable diagnostic code safe to write to the catalog. */
    val code: String get() = kind.name
}

/** Raised at the storage boundary so a fault keeps its type instead of becoming a bare message. */
internal class StorageAccessException(val faultKind: StorageFaultKind) : RuntimeException(faultKind.name)

internal sealed class StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>()
    data class Failure(val fault: StorageFault) : StorageResult<Nothing>()

    fun valueOrNull(): T? = (this as? Success)?.value
    fun faultOrNull(): StorageFault? = (this as? Failure)?.fault
}

internal object StorageFaultPolicy {
    /** Classify a boundary failure once. Exception text is never copied into the typed fault. */
    fun classify(error: Throwable, cancelled: Boolean): StorageFault {
        if (cancelled) return StorageFault(StorageFaultKind.CANCELLED)
        return when (error) {
            is StorageAccessException -> StorageFault(error.faultKind)
            is SecurityException -> StorageFault(StorageFaultKind.PERMISSION_LOST)
            is IOException -> StorageFault(
                if (indicatesNoSpace(error.message)) StorageFaultKind.DISK_FULL
                else StorageFaultKind.PROVIDER_DISCONNECTED,
            )
            else -> StorageFault(StorageFaultKind.UNKNOWN)
        }
    }

    private fun indicatesNoSpace(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val text = message.lowercase()
        return listOf("no space", "enospc", "quota", "insufficient storage", "disk full", "storage full")
            .any { text.contains(it) }
    }

    /** A single bounded attempt: no retry loop, with cancellation checked before the step. */
    fun <T> attempt(cancelled: () -> Boolean, step: () -> T): StorageResult<T> = try {
        if (cancelled()) StorageResult.Failure(StorageFault(StorageFaultKind.CANCELLED))
        else StorageResult.Success(step())
    } catch (error: Exception) {
        StorageResult.Failure(classify(error, cancelled()))
    }
}

internal enum class StorageDestination { INTERNAL, SAF }

internal object RecordingSinkPolicy {
    /**
     * Choose exactly one destination. A selected SAF tree is authoritative: a disconnect or quota
     * failure is a typed fault, never a silent redirect to internal storage (and vice versa).
     */
    fun selectDestination(
        safTreeSelected: Boolean,
        safAvailableBytes: Long?,
        internalAvailableBytes: Long,
        minFreeBytes: Long,
    ): StorageResult<StorageDestination> {
        if (safTreeSelected) {
            val available = safAvailableBytes
                ?: return StorageResult.Failure(StorageFault(StorageFaultKind.PROVIDER_DISCONNECTED))
            if (available < minFreeBytes) {
                return StorageResult.Failure(StorageFault(StorageFaultKind.DISK_FULL))
            }
            return StorageResult.Success(StorageDestination.SAF)
        }
        if (internalAvailableBytes < minFreeBytes) {
            return StorageResult.Failure(StorageFault(StorageFaultKind.DISK_FULL))
        }
        return StorageResult.Success(StorageDestination.INTERNAL)
    }
}

/** Fixed-memory guard that refuses to let one capture write past its storage budget. */
internal class BoundedOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Long,
) : OutputStream() {
    init {
        require(maxBytes >= 0) { "Negative storage bound" }
    }

    var count: Long = 0
        private set

    override fun write(oneByte: Int) {
        reserve(1)
        delegate.write(oneByte)
        count = Math.addExact(count, 1L)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        reserve(length.toLong())
        delegate.write(buffer, offset, length)
        count = Math.addExact(count, length.toLong())
    }

    private fun reserve(additional: Long) {
        if (additional < 0 || additional > maxBytes - count) {
            throw StorageAccessException(StorageFaultKind.DISK_FULL)
        }
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}

/** The only terminal transition a storage attempt may write to the catalog. */
internal data class StorageTerminalDecision(
    val state: String,
    val finalize: Boolean,
    val discardFile: Boolean,
    val fault: StorageFault?,
    val completedLocation: String?,
)

internal object StorageCatalogPolicy {
    /** A fault can only produce FAILED / PARTIAL / CANCELLED, never COMPLETED or a final path. */
    fun decide(fault: StorageFault?, bytesWritten: Long, cancelled: Boolean): StorageTerminalDecision {
        val effective = if (cancelled) StorageFault(StorageFaultKind.CANCELLED) else fault
        if (effective != null) {
            val state = when (effective.kind) {
                StorageFaultKind.CANCELLED -> "CANCELLED"
                else -> if (bytesWritten > 0L) "PARTIAL" else "FAILED"
            }
            return StorageTerminalDecision(
                state = state,
                finalize = false,
                discardFile = state == "CANCELLED" || bytesWritten <= 0L,
                fault = effective,
                completedLocation = null,
            )
        }
        return if (bytesWritten > 0L) {
            StorageTerminalDecision("COMPLETED", true, false, null, null)
        } else {
            StorageTerminalDecision(
                "FAILED", false, true, StorageFault(StorageFaultKind.UNKNOWN), null,
            )
        }
    }
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
        // Inspect once, then decide on exactly one destination. A failed SAF check is a typed
        // fault and must never be downgraded into a write to internal storage.
        val info = treeUri?.let { SafStorageInspector.inspect(context, it) }
        val directory = (context.getExternalFilesDir("recordings")
            ?: File(context.filesDir, "recordings"))
        val selection = RecordingSinkPolicy.selectDestination(
            safTreeSelected = treeUri != null,
            safAvailableBytes = info?.availableBytes,
            internalAvailableBytes = if (treeUri == null) directory.usableSpace else 0L,
            minFreeBytes = RecordingPolicy.DEFAULT_MIN_FREE_BYTES,
        )
        when (selection) {
            is StorageResult.Failure -> throw StorageAccessException(selection.fault.kind)
            is StorageResult.Success -> when (selection.value) {
                StorageDestination.SAF -> return SafRecordingSink(context, info!!, safeId, extension)
                StorageDestination.INTERNAL -> {
                    directory.mkdirs()
                    require(directory.isDirectory) { "Recording storage unavailable" }
                    return InternalRecordingSink(directory, safeId, extension)
                }
            }
        }
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
        // Hard cap at the storage available when the capture opened: a runaway write becomes a
        // typed DISK_FULL instead of an unbounded stream.
        return BoundedOutputStream(output, directory.usableSpace)
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
        return BoundedOutputStream(output, lastReportedAvailable)
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

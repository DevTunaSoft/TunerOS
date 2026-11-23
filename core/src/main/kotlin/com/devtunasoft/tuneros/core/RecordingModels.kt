package com.devtunasoft.tuneros.core

/**
 * Platform-neutral DVR contracts. Android services/schedulers persist and execute these records;
 * the core model intentionally contains no Activity, player, or in-memory media state.
 */
enum class RecordingState { SCHEDULED, RECORDING, COMPLETED, PARTIAL, FAILED, CANCELLED }

/** Padding moves a capture window outwards; leaving Long range saturates so it stays ordered and never drops a conflict. */
private fun paddedStart(epochSeconds: Long, paddingSeconds: Int): Long =
    if (epochSeconds < Long.MIN_VALUE + paddingSeconds) Long.MIN_VALUE else epochSeconds - paddingSeconds

private fun paddedEnd(epochSeconds: Long, paddingSeconds: Int): Long =
    if (epochSeconds > Long.MAX_VALUE - paddingSeconds) Long.MAX_VALUE else epochSeconds + paddingSeconds

data class RecordingRequest(
    val id: String,
    val channelId: String,
    val title: String,
    val streamUrl: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val startPaddingSeconds: Int = 0,
    val endPaddingSeconds: Int = 0,
) {
    init {
        require(id.isNotBlank())
        require(channelId.isNotBlank())
        require(title.isNotBlank())
        require(streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))
        require(endEpochSeconds > startEpochSeconds)
        require(startPaddingSeconds >= 0)
        require(endPaddingSeconds >= 0)
    }

    val captureStartEpochSeconds: Long get() = paddedStart(startEpochSeconds, startPaddingSeconds)
    val captureEndEpochSeconds: Long get() = paddedEnd(endEpochSeconds, endPaddingSeconds)
}

data class RecordingProgress(
    val requestId: String,
    val state: RecordingState,
    val bytesWritten: Long = 0,
    val startedEpochSeconds: Long? = null,
    val stoppedEpochSeconds: Long? = null,
    val failureReason: String? = null,
) {
    init { require(bytesWritten >= 0) }
}

object RecordingPolicy {
    const val COPY_BUFFER_BYTES = 64 * 1024
    const val DEFAULT_MIN_FREE_BYTES = 512L * 1024 * 1024

    /**
     * A document belongs to a removable/shared-storage root only at a path
     * boundary. A naïve startsWith(root) can select the wrong drive's capacity
     * ("1234" must not match "12345"). No allocations or I/O.
     */
    fun isDocumentOnRoot(documentId: String, rootDocumentId: String): Boolean {
        if (rootDocumentId.isEmpty()) return false
        if (documentId == rootDocumentId) return true
        return if (rootDocumentId.endsWith(':') || rootDocumentId.endsWith('/')) {
            documentId.startsWith(rootDocumentId)
        } else {
            documentId.startsWith(rootDocumentId, 0) &&
                documentId.length > rootDocumentId.length &&
                (documentId[rootDocumentId.length] == ':' ||
                    documentId[rootDocumentId.length] == '/')
        }
    }

    /** Missing, invalid or exhausted provider capacity fails closed. */
    fun remainingFreeBytes(reportedAvailable: Long?, writtenSinceReport: Long): Long {
        require(writtenSinceReport >= 0)
        if (reportedAvailable == null || reportedAvailable <= 0) return 0L
        return if (writtenSinceReport >= reportedAvailable) 0L
            else reportedAvailable - writtenSinceReport
    }

    fun fromProgram(
        channel: Channel,
        program: GuideProgram,
        id: String,
        startPaddingSeconds: Int = 0,
        endPaddingSeconds: Int = 0,
    ): RecordingRequest = RecordingRequest(
        id = id,
        channelId = channel.id,
        title = program.title,
        streamUrl = channel.streamUrl,
        startEpochSeconds = program.startEpochSeconds,
        endEpochSeconds = program.endEpochSeconds,
        startPaddingSeconds = startPaddingSeconds,
        endPaddingSeconds = endPaddingSeconds,
    )

    fun conflicts(left: RecordingRequest, right: RecordingRequest): Boolean =
        left.captureStartEpochSeconds < right.captureEndEpochSeconds &&
            right.captureStartEpochSeconds < left.captureEndEpochSeconds

    /**
     * A capture that reached its stop without writing a byte is a failure, never COMPLETED.
     * A COMPLETED entry with an empty file would be offered for playback.
     */
    fun finalState(requested: RecordingState, bytesWritten: Long): RecordingState {
        require(bytesWritten >= 0)
        return if (requested == RecordingState.COMPLETED && bytesWritten == 0L) {
            RecordingState.FAILED
        } else {
            requested
        }
    }
}

package com.devtunasoft.tuneros.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Bounded-memory byte copier used by raw/progressive DVR capture.
 *
 * The caller owns network/storage lifecycle. This class never buffers an entire stream,
 * never closes caller-owned streams, and checks cancellation/free-space between writes.
 */
class StreamRecorder(
    private val bufferBytes: Int = RecordingPolicy.COPY_BUFFER_BYTES,
    private val minFreeBytes: Long = RecordingPolicy.DEFAULT_MIN_FREE_BYTES,
) {
    init {
        require(bufferBytes in 4 * 1024..1024 * 1024)
        require(minFreeBytes >= 0)
    }

    enum class StopReason { END_OF_STREAM, CANCELLED, LOW_STORAGE }

    data class Result(val bytesWritten: Long, val stopReason: StopReason)

    fun copy(
        input: InputStream,
        output: OutputStream,
        freeBytes: () -> Long,
        cancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
        buffer: ByteArray = ByteArray(bufferBytes),
    ): Result {
        require(buffer.size >= bufferBytes) {
            "Copy buffer must be at least $bufferBytes bytes"
        }
        require(freeBytes() >= minFreeBytes) { "Insufficient free storage" }
        var total = 0L
        while (true) {
            if (cancelled()) return Result(total, StopReason.CANCELLED)
            if (freeBytes() < minFreeBytes) return Result(total, StopReason.LOW_STORAGE)
            val count = input.read(buffer, 0, bufferBytes)
            if (count < 0) return Result(total, StopReason.END_OF_STREAM)
            if (count == 0) continue
            output.write(buffer, 0, count)
            total += count
            onProgress(total)
        }
    }
}

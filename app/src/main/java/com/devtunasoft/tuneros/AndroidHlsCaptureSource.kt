package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsCaptureSource
import com.devtunasoft.tuneros.core.HlsPlaylistParserCore
import com.devtunasoft.tuneros.core.LoadedHlsPlaylist
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.StreamRecorder
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection

internal class AndroidHlsCaptureSource(
    private val openConnection: (String, HlsByteRange?) -> HttpURLConnection,
    private val freeBytes: () -> Long,
) : HlsCaptureSource {
    private val recorder = StreamRecorder()
    private val segmentBuffer = ByteArray(RecordingPolicy.COPY_BUFFER_BYTES)
    override fun loadPlaylist(
        url: String,
        afterSequenceExclusive: Long?,
    ): LoadedHlsPlaylist {
        val connection = openConnection(url, null)
        return try {
            val playlist = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                HlsPlaylistParserCore.parse(it, afterSequenceExclusive)
            }
            LoadedHlsPlaylist(
                effectiveUrl = connection.url.toString(),
                playlist = playlist,
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun copySegment(
        url: String,
        byteRange: HlsByteRange?,
        output: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamRecorder.Result {
        val connection = openConnection(url, byteRange)
        return try {
            // Content-Length is meaningful for identity bodies, not transparently
            // decoded compressed responses. Unknown lengths remain supported.
            val encoding = connection.contentEncoding
            val expectedBytes = if (encoding == null ||
                encoding.equals("identity", ignoreCase = true)
            ) connection.contentLengthLong else -1L
            val result = connection.inputStream.use { input ->
                recorder.copy(
                    input = input,
                    output = output,
                    freeBytes = freeBytes,
                    cancelled = cancelled,
                    onProgress = onProgress,
                    buffer = segmentBuffer,
                )
            }
            // A clean EOF does not imply a complete media segment. Keep written
            // bytes for the catalog's PARTIAL result and never advance HLS sequence
            // after a declared HTTP body was truncated.
            if (result.stopReason == StreamRecorder.StopReason.END_OF_STREAM &&
                expectedBytes >= 0L && result.bytesWritten != expectedBytes
            ) {
                throw IOException("HLS segment HTTP body length mismatch")
            }
            result
        } finally {
            connection.disconnect()
        }
    }
}

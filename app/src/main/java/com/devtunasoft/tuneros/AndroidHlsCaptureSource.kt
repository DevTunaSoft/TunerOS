package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsCaptureSource
import com.devtunasoft.tuneros.core.HlsPlaylistParserCore
import com.devtunasoft.tuneros.core.LoadedHlsPlaylist
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.StreamRecorder
import java.io.OutputStream
import java.net.HttpURLConnection

internal class AndroidHlsCaptureSource(
    private val openConnection: (String, HlsByteRange?) -> HttpURLConnection,
    private val freeBytes: () -> Long,
) : HlsCaptureSource {
    private val recorder = StreamRecorder()
    private val segmentBuffer = ByteArray(RecordingPolicy.COPY_BUFFER_BYTES)
    override fun loadPlaylist(url: String): LoadedHlsPlaylist {
        val connection = openConnection(url, null)
        return try {
            val playlist = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                HlsPlaylistParserCore.parse(it)
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
            connection.inputStream.use { input ->
                recorder.copy(
                    input = input,
                    output = output,
                    freeBytes = freeBytes,
                    cancelled = cancelled,
                    onProgress = onProgress,
                    buffer = segmentBuffer,
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}

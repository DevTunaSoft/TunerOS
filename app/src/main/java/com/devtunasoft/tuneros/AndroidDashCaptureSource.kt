package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.DashCaptureSource
import com.devtunasoft.tuneros.core.DashManifest
import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.LoadedDashManifest
import com.devtunasoft.tuneros.core.StreamRecorder
import java.io.IOException
import java.io.OutputStream
import java.io.Reader
import java.net.HttpURLConnection

/**
 * DASH network access for the recorder. Manifests are read with a hard size bound. Segments are copied by the same
 * code as HLS segments, so the body-length rule applies to them too.
 */
internal class AndroidDashCaptureSource(
    private val openConnection: (String, HlsByteRange?) -> HttpURLConnection,
    freeBytes: () -> Long,
) : DashCaptureSource {
    private val segments = AndroidHlsCaptureSource(openConnection, freeBytes)

    override fun loadManifest(url: String): LoadedDashManifest {
        val connection = openConnection(url, null)
        return try {
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { readBounded(it) }
            LoadedDashManifest(effectiveUrl = connection.url.toString(), text = text)
        } finally {
            connection.disconnect()
        }
    }

    override fun copySegment(
        url: String,
        output: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamRecorder.Result = segments.copySegment(url, null, output, cancelled, onProgress)

    private fun readBounded(reader: Reader): String {
        val buffer = CharArray(DashManifest.MAX_MANIFEST_CHARS + 1)
        var total = 0
        while (total < buffer.size) {
            val read = reader.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        if (total > DashManifest.MAX_MANIFEST_CHARS) throw IOException("DASH manifest is too large")
        return String(buffer, 0, total)
    }
}

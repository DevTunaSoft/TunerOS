package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.DashCaptureSource
import com.devtunasoft.tuneros.core.DashManifest
import com.devtunasoft.tuneros.core.DashSegmentIndex
import com.devtunasoft.tuneros.core.DashSegmentNotPublishedException
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

    /**
     * The bytes of a SegmentBase index. The range goes through the same checked connection as a segment, so the
     * response must be a partial response, and exactly the requested length is read.
     */
    override fun loadRange(url: String, range: HlsByteRange): ByteArray {
        val length = range.length
        if (length <= 0 || length > DashSegmentIndex.MAX_INDEX_BYTES) throw IOException("DASH index range is out of bounds")
        val connection = openConnection(url, range)
        return try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                throw if (status in 200..299) IOException("DASH index range was not honoured") else HttpStatusException(status)
            }
            val bytes = ByteArray(length.toInt())
            connection.inputStream.use { input ->
                var filled = 0
                while (filled < bytes.size) {
                    val read = input.read(bytes, filled, bytes.size - filled)
                    if (read < 0) throw IOException("DASH index range is short")
                    filled += read
                }
            }
            bytes
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
    ): StreamRecorder.Result = try {
        segments.copySegment(url, byteRange, output, cancelled, onProgress)
    } catch (status: HttpStatusException) {
        // A 404 is answered before the body, so nothing has been written for this segment.
        if (status.code == HttpURLConnection.HTTP_NOT_FOUND) throw DashSegmentNotPublishedException() else throw status
    }

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

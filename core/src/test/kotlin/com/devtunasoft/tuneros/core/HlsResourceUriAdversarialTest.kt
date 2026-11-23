package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Adversarial HLS resource resolution. Every address below is a synthetic .invalid fixture and every marker is a
 * canary, never a provider URL or credential. A hostile playlist must not copy any bytes or echo its raw text.
 */
class HlsResourceUriAdversarialTest {
    private class RecordingSource(private val playlist: LoadedHlsPlaylist) : HlsCaptureSource {
        val copied = ArrayList<String>()

        override fun loadPlaylist(url: String, afterSequenceExclusive: Long?): LoadedHlsPlaylist = playlist

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            output.write(1)
            onProgress(1L)
            return StreamRecorder.Result(1L, StreamRecorder.StopReason.END_OF_STREAM)
        }

        override fun loadKey(url: String): ByteArray = error("this fixture has no key material")
    }

    private fun sourceOf(text: String): RecordingSource {
        val snapshot = HlsPlaylistParserCore.parse(StringReader(text))
        check(snapshot is HlsMediaPlaylist || snapshot is HlsMasterPlaylist)
        return RecordingSource(LoadedHlsPlaylist("https://fixture.invalid/live.m3u8", snapshot))
    }

    private fun record(source: RecordingSource) = HlsCaptureEngine().record(
        "https://fixture.invalid/live.m3u8",
        ByteArrayOutputStream(),
        1_000,
        source,
        { false },
        { 100 },
        {},
    )

    private fun assertNoCanary(error: Throwable, marker: String) {
        assertFalse(
            error.toString().contains(marker),
            "the raw playlist URI reached a diagnostic: $error",
        )
    }

    @Test fun hostileSegmentUriIsRefusedWithoutEchoingThePlaylistText() {
        val marker = "SYNTHETIC_SEGMENT_CANARY"
        val source = sourceOf("#EXTM3U\n#EXTINF:4,\nseg\u0007$marker.ts\n")

        val error = assertFailsWith<IllegalArgumentException> { record(source) }
        assertNoCanary(error, marker)
        assertEquals(0, source.copied.size, "a malformed resource must not be copied")
    }

    @Test fun hostileKeyUriIsRefusedWithoutEchoingThePlaylistText() {
        val marker = "SYNTHETIC_KEY_CANARY"
        val source = sourceOf(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\u0007$marker.bin\"\n#EXTINF:4,\nseg.ts\n",
        )

        val error = assertFailsWith<IllegalArgumentException> { record(source) }
        assertNoCanary(error, marker)
        assertEquals(0, source.copied.size, "a malformed key resource must not trigger a segment copy")
    }

    @Test fun hostileVariantUriIsRefusedWithoutEchoingTheMasterPlaylist() {
        val marker = "SYNTHETIC_VARIANT_CANARY"
        val source = sourceOf("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nvariant\u0007$marker.m3u8\n")

        val error = assertFailsWith<IllegalArgumentException> { record(source) }
        assertNoCanary(error, marker)
        assertEquals(0, source.copied.size, "a malformed variant must not be requested")
    }
}

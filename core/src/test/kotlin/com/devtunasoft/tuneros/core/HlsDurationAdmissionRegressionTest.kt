package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic regressions for the timeshift duration-admission guard in [HlsCaptureEngine].
 *
 * The existing null-duration test covers only a missing EXTINF value. A synthetic playlist can
 * also carry a non-finite or non-positive duration token (NaN, Infinity, zero, negative), which the
 * bounded manifest parser admits as a Double. A timeshift must refuse such a segment before any
 * media byte is copied, while ordinary recording without a segment listener is unaffected.
 * All fixtures are synthetic; no provider URL, credential or media is used.
 */
class HlsDurationAdmissionRegressionTest {
    private class FakeSource(private val playlist: LoadedHlsPlaylist) : HlsCaptureSource {
        val copied = ArrayList<String>()
        private val queue = ArrayDeque(listOf(playlist))

        override fun loadPlaylist(url: String, afterSequenceExclusive: Long?): LoadedHlsPlaylist =
            if (queue.size > 1) queue.removeFirst() else queue.first()

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            output.write(byteArrayOf(0x41, 0x42))
            onProgress(2L)
            return StreamRecorder.Result(2L, StreamRecorder.StopReason.END_OF_STREAM)
        }

        override fun loadKey(url: String): ByteArray = error("no keys in a synthetic clear playlist")
    }

    private class Observed(
        val result: HlsCaptureEngine.Result,
        val source: FakeSource,
        val writtenBytes: Int,
    )

    private class Listener : HlsSegmentListener {
        val completed = ArrayList<Long>()
        override fun onSegmentComplete(sequence: Long, durationSeconds: Double, bytes: Long, discontinuity: Boolean) {
            completed += sequence
        }
    }

    private fun playlistWith(duration: Double): LoadedHlsPlaylist {
        val url = "https://synthetic.invalid/duration.m3u8"
        return LoadedHlsPlaylist(
            url,
            HlsMediaPlaylist(
                mediaSequence = 4L,
                targetDurationSeconds = 2L,
                endList = true,
                segments = listOf(
                    HlsSegment(4L, "seg-4.ts", duration, null, false, "NONE", null, null),
                ),
            ),
        )
    }

    private fun record(playlist: LoadedHlsPlaylist, listener: HlsSegmentListener?): Observed {
        val source = FakeSource(playlist)
        val output = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(
            playlist.effectiveUrl, output, 1_000L, source, { false }, { 100L }, {},
            segmentListener = listener,
        )
        return Observed(result, source, output.size())
    }

    private val unusable = listOf(
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        0.0,
        -0.0,
        -1.0,
        -Double.MIN_VALUE,
    )

    @Test fun everyUnusableDurationIsRefusedBeforeAnySegmentCopyWhenTimeshiftIsAttached() {
        val listener = Listener()
        unusable.forEach { duration ->
            val seen = record(playlistWith(duration), listener)
            assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, seen.result.stopReason, "duration $duration")
            assertEquals("HLS segment duration is not usable for timeshift", seen.result.detail, "duration $duration")
            assertEquals(0L, seen.result.bytesWritten, "duration $duration")
            assertEquals(0, seen.writtenBytes, "duration $duration")
            assertEquals(null, seen.result.lastSequence, "duration $duration")
            assertEquals(emptyList<String>(), seen.source.copied, "duration $duration")
        }
        assertEquals(emptyList<Long>(), listener.completed)
    }

    @Test fun theSameDurationsAreStillCopiedForPlainRecordingWithoutATimeshiftListener() {
        unusable.forEach { duration ->
            val seen = record(playlistWith(duration), null)
            assertEquals(HlsCaptureEngine.StopReason.END_LIST, seen.result.stopReason, "duration $duration")
            assertEquals(2L, seen.result.bytesWritten, "duration $duration")
            assertEquals(2, seen.writtenBytes, "duration $duration")
            assertEquals(listOf("https://synthetic.invalid/seg-4.ts"), seen.source.copied, "duration $duration")
        }
    }

    @Test fun anAdversarialExtinfTokenReachesTheGuardWithItsExactDoubleValue() {
        val manifest = "#EXTM3U\n#EXTINF:NaN,\nseg-4.ts\n"
        val parsed = HlsPlaylistParserCore.parse(manifest.reader()) as HlsMediaPlaylist
        assertEquals(1, parsed.segments.size)
        assertTrue(parsed.segments.single().durationSeconds!!.isNaN())

        val url = "https://synthetic.invalid/from-parser.m3u8"
        val source = object : HlsCaptureSource by FakeSource(LoadedHlsPlaylist(url, parsed)) {}
        val output = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(
            url, output, 1_000L, source, { false }, { 100L }, {},
            segmentListener = Listener(),
        )
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals(0, output.size())
    }
}

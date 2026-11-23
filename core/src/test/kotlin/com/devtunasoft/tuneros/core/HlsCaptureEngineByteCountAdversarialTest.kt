package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial coverage for byte counts reported by an HLS capture source.
 *
 * A source reports how many bytes its copy wrote. A negative count or one that would overflow the running total must
 * stop the recording with a structured refusal, never wrap into a negative disk total or escape as an unchecked
 * exception. CORE-15 added this guard to the DASH engine; the HLS engine had no equivalent. All addresses below are
 * synthetic example.test names; no provider URL, credential or media payload appears here.
 */
class HlsCaptureEngineByteCountAdversarialTest {
    private val playlistUrl = "https://example.test/archive.m3u8"

    /** Writes one real byte but reports a scripted count, including a scripted progress value. */
    private class ReportingSource(
        private val playlist: LoadedHlsPlaylist,
        private val reported: Map<String, Long>,
    ) : HlsCaptureSource {
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
            output.write(7)
            val count = reported[url] ?: 1L
            onProgress(count)
            return StreamRecorder.Result(count, StreamRecorder.StopReason.END_OF_STREAM)
        }

        override fun loadKey(url: String): ByteArray = error("this fixture has no keys")
    }

    private fun vodPlaylist(vararg sequences: Long): LoadedHlsPlaylist = LoadedHlsPlaylist(
        playlistUrl,
        HlsMediaPlaylist(
            mediaSequence = sequences.first(),
            targetDurationSeconds = 4L,
            endList = true,
            segments = sequences.map { sequence ->
                HlsSegment(sequence, "$sequence.ts", 4.0, null, false, "NONE", null, null)
            },
        ),
    )

    private fun record(
        source: HlsCaptureSource,
        progress: MutableList<Long>,
    ): HlsCaptureEngine.Result = HlsCaptureEngine().record(
        playlistUrl,
        ByteArrayOutputStream(),
        1_000,
        source,
        { false },
        { 100 },
        {},
        onProgress = { progress += it },
    )

    @Test fun aNegativeReportedByteCountStopsWithANonNegativeTotal() {
        val source = ReportingSource(vodPlaylist(1L), mapOf("https://example.test/1.ts" to -1L))
        val progress = ArrayList<Long>()

        val result = record(source, progress)

        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("HLS byte count is invalid", result.detail)
        assertEquals(0L, result.bytesWritten)
        assertTrue(progress.all { it >= 0L }, "progress went negative: $progress")
    }

    @Test fun anOverflowingReportedByteCountStopsWithoutAnUncheckedException() {
        val source = ReportingSource(
            vodPlaylist(1L, 2L),
            mapOf("https://example.test/2.ts" to Long.MAX_VALUE),
        )
        val progress = ArrayList<Long>()

        val result = record(source, progress)

        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("HLS byte count is invalid", result.detail)
        assertEquals(1L, result.bytesWritten)
        assertTrue(progress.all { it >= 0L }, "progress went negative: $progress")
        assertTrue(Long.MAX_VALUE in progress, "progress did not saturate: $progress")
    }

    @Test fun anInvalidInitializationByteCountStopsBeforeAnyMediaIsRequested() {
        val url = "https://example.test/init.m3u8"
        val media = HlsSegment(1L, "1.ts", 4.0, null, false, "NONE", null, "init.mp4")
        val playlist = LoadedHlsPlaylist(
            url,
            HlsMediaPlaylist(1L, 4L, true, listOf(media)),
        )
        val source = object : HlsCaptureSource {
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
                output.write(7)
                val count = if (url.endsWith("init.mp4")) -2L else 1L
                onProgress(count)
                return StreamRecorder.Result(count, StreamRecorder.StopReason.END_OF_STREAM)
            }

            override fun loadKey(url: String): ByteArray = error("this fixture has no keys")
        }
        val progress = ArrayList<Long>()

        val result = HlsCaptureEngine().record(
            url, ByteArrayOutputStream(), 1_000, source, { false }, { 100 }, {},
            onProgress = { progress += it },
        )

        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("HLS byte count is invalid", result.detail)
        assertEquals(0L, result.bytesWritten)
        assertEquals(listOf("https://example.test/init.mp4"), source.copied)
        assertTrue(progress.all { it >= 0L }, "progress went negative: $progress")
    }
}

package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CORE-45 adversarial coverage for the media-sequence reset guard in [HlsCaptureEngine].
 *
 * Audit boundary: base HlsMediaSequenceRegressionTest covers a repeated parser tag, CORE-34 covers the
 * decimal-integer sign of EXT-X-MEDIA-SEQUENCE, and CORE-14/CORE-40 own HLS diagnostics and other transports.
 * No base or CORE-01..44 test asserts this engine-level decision: a refreshed playlist whose latest
 * sequence is below the last segment already written. All addresses are synthetic scratch fixtures.
 */
class HlsMediaSequenceResetRegressionTest {
    private val url = "https://example.test/scratch/live.m3u8"

    private fun segment(sequence: Long, name: String = "$sequence.ts") = HlsSegment(
        sequence = sequence,
        uri = name,
        durationSeconds = 4.0,
        byteRange = null,
        discontinuity = false,
        keyMethod = "NONE",
        keyUri = null,
        initializationMapUri = null,
    )

    private fun live(first: Long, count: Int, endList: Boolean = false) = HlsMediaPlaylist(
        mediaSequence = first,
        targetDurationSeconds = 4,
        endList = endList,
        segments = (0 until count).map { index -> segment(first + index) },
    )

    private class ScriptedSource(
        url: String,
        playlists: List<LoadedHlsPlaylist>,
    ) : HlsCaptureSource {
        private val queue = ArrayDeque(playlists)
        val loadedUrls = ArrayList<String>()
        val requestedAfter = ArrayList<Long?>()
        val copied = ArrayList<String>()

        override fun loadPlaylist(url: String, afterSequenceExclusive: Long?): LoadedHlsPlaylist {
            loadedUrls += url
            requestedAfter += afterSequenceExclusive
            check(queue.isNotEmpty()) { "test fixture ran out of playlists" }
            return if (queue.size == 1) queue.first() else queue.removeFirst()
        }

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            val bytes = byteArrayOf(7)
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }

        override fun loadKey(url: String): ByteArray = error("no keys in this fixture")
    }

    private fun record(
        playlists: List<LoadedHlsPlaylist>,
        end: Long = 1_000L,
    ): Pair<HlsCaptureEngine.Result, ScriptedSource> {
        val source = ScriptedSource(url, playlists)
        val output = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(
            initialUrl = url,
            output = output,
            endEpochSeconds = end,
            source = source,
            cancelled = { false },
            nowEpochSeconds = { 100L },
            sleepMillis = {},
        )
        return result to source
    }

    private fun loaded(playlist: HlsPlaylistSnapshot) = LoadedHlsPlaylist(url, playlist)

    @Test
    fun aRefreshedPlaylistWithALowerLatestSequenceStopsBeforeWritingMoreBytes() {
        val first = loaded(live(10, 2))
        val reset = loaded(live(5, 2))
        val (result, source) = record(listOf(first, reset))

        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("HLS media sequence reset", result.detail)
        assertEquals(11L, result.lastSequence, "the last written segment must stay reported")
        assertEquals(1L, result.bytesWritten)
        assertEquals(listOf("https://example.test/scratch/11.ts"), source.copied)
        assertEquals(listOf(null, 11L), source.requestedAfter)
        assertEquals(2, source.loadedUrls.size, "the reset must stop the refresh loop at once")
    }

    @Test
    fun anUnchangedLatestSequenceIsNotMistakenForAReset() {
        val first = loaded(live(10, 2))
        val unchanged = loaded(live(10, 2, endList = true))
        val (result, source) = record(listOf(first, unchanged))

        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(null, result.detail)
        assertEquals(11L, result.lastSequence)
        assertEquals(1L, result.bytesWritten)
        assertEquals(2, source.loadedUrls.size)
    }

    @Test
    fun aPlaylistWithoutALatestSequenceIsNotMistakenForAReset() {
        val first = loaded(live(10, 2))
        val noLatest = loaded(
            HlsMediaPlaylist(
                mediaSequence = 5,
                targetDurationSeconds = 4,
                endList = true,
                segments = listOf(segment(5)),
                latestSequence = null,
            ),
        )
        val (result, _) = record(listOf(first, noLatest))

        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(null, result.detail)
        assertEquals(11L, result.lastSequence)
    }

    @Test
    fun theLargestRepresentableSequenceIsNotMistakenForAReset() {
        val maximum = Long.MAX_VALUE
        val first = loaded(live(maximum, 1))
        val sameEdge = loaded(live(maximum, 1, endList = true))
        val (result, _) = record(listOf(first, sameEdge))

        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(null, result.detail)
        assertEquals(maximum, result.lastSequence)
    }

    @Test
    fun aResetRefusalCarriesNoSyntheticAddressMaterial() {
        val marker = String(
            charArrayOf('C', 'O', 'R', 'E', '4', '5', '-', 'S', 'C', 'R', 'A', 'T', 'C', 'H'),
        )
        val first = loaded(live(10, 2))
        val hostile = loaded(
            HlsMediaPlaylist(
                mediaSequence = 5,
                targetDurationSeconds = 4,
                endList = false,
                segments = listOf(segment(5, "$marker/secret.ts"), segment(6)),
            ),
        )
        val (result, source) = record(listOf(first, hostile))

        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("HLS media sequence reset", result.detail)
        assertFalse(result.detail.orEmpty().contains(marker))
        assertFalse(result.detail.orEmpty().contains("example.test"))
        assertFalse(result.detail.orEmpty().contains("secret"))
        assertFalse(source.copied.any { it.contains(marker) }, "the hostile segment was never requested")
        assertTrue(source.loadedUrls.size == 2)
    }
}

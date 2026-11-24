package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Stream-recovery regressions for the seam between the timeshift ring and the
 * bounded HLS manifest parser: the ring publishes its own window as a live
 * media playlist, which the production parser reads back. No sibling CORE
 * branch covers this cross-module round-trip; both sides are exercised here
 * with synthetic fixtures only (no URLs, credentials or media).
 */
class TimeshiftPlaylistRoundTripRegressionTest {

    private fun window() = TimeshiftWindow(maxSegments = 8, maxBytes = 1_000_000)

    private fun segment(
        sequence: Long,
        duration: Double,
        name: String,
        discontinuity: Boolean = false,
        programDateMillis: Long? = null,
    ) = TimeshiftWindow.Segment(
        sequence = sequence,
        durationSeconds = duration,
        bytes = 100L,
        fileName = name,
        discontinuity = discontinuity,
        programDateMillis = programDateMillis,
    )

    @Test fun roundTripKeepsSequencesDurationsDiscontinuityAndSegmentFiles() {
        val ring = window()
        ring.add(segment(40, 4.0, "seg-040.ts"))
        ring.add(segment(41, 6.5, "seg-041.ts", discontinuity = true))
        ring.add(segment(42, 2.25, "seg-042.ts", programDateMillis = 1_700_000_000_123L))

        val parsed = HlsPlaylistParserCore.parse(
            ring.playlist().reader(),
            afterSequenceExclusive = 39L,
        ) as HlsMediaPlaylist

        assertEquals(listOf(40L, 41L, 42L), parsed.segments.map { it.sequence })
        assertEquals(listOf(4.0, 6.5, 2.25), parsed.segments.map { it.durationSeconds })
        assertEquals(listOf("seg-040.ts", "seg-041.ts", "seg-042.ts"), parsed.segments.map { it.uri })
        assertEquals(listOf(false, true, false), parsed.segments.map { it.discontinuity })
        assertEquals(7L, parsed.targetDurationSeconds)
        assertEquals(40L, parsed.mediaSequence)
        assertEquals(42L, parsed.latestSequence)
    }

    @Test fun theParserReturnsTheWholeWindowAndLeavesLiveEdgeChoiceToTheCaller() {
        val ring = window()
        ring.add(segment(0, 2.0, "seg-000.ts"))
        ring.add(segment(1, 2.0, "seg-001.ts"))

        // No end list: the playlist is live. The parser deliberately returns
        // every segment when no sequence filter is supplied; choosing the live
        // edge is HlsCaptureEngine's decision, not the parser's.
        val parsed = HlsPlaylistParserCore.parse(ring.playlist().reader()) as HlsMediaPlaylist

        assertEquals(false, parsed.endList)
        assertEquals(listOf(0L, 1L), parsed.segments.map { it.sequence })
        assertEquals(listOf(0L, 1L), parsed.segments.map { it.uri.let { name -> name.removePrefix("seg-").removeSuffix(".ts").toLong() } })
    }

    @Test fun theResultCapAppliesToRetainedSegmentsNotToScannedHistory() {
        val total = HlsPlaylistParserCore.MAX_RESULT_SEGMENTS + 5
        val text = buildString {
            append("#EXTM3U\n")
            repeat(total) { index -> append("#EXTINF:1.0,\nseg-$index.ts\n") }
        }
        val cutoff = HlsPlaylistParserCore.MAX_RESULT_SEGMENTS - 1L
        val parsed = HlsPlaylistParserCore.parse(
            text.reader(),
            afterSequenceExclusive = cutoff,
        ) as HlsMediaPlaylist
        assertEquals(5, parsed.segments.size)
        assertEquals(cutoff + 1, parsed.segments.first().sequence)

        assertFailsWith<IllegalArgumentException> {
            HlsPlaylistParserCore.parse(text.reader())
        }
    }

    @Test fun programDateTagsDoNotDisturbTheBoundedParse() {
        val ring = window()
        ring.add(segment(7, 3.0, "seg-007.ts", programDateMillis = Long.MAX_VALUE / 4))
        ring.add(segment(8, 3.0, "seg-008.ts", programDateMillis = -(Long.MAX_VALUE / 4)))

        val playlist = ring.playlist()
        assertTrue(playlist.contains("#EXT-X-PROGRAM-DATE-TIME:"))
        val parsed = HlsPlaylistParserCore.parse(
            playlist.reader(),
            afterSequenceExclusive = 6L,
        ) as HlsMediaPlaylist
        assertEquals(listOf(7L, 8L), parsed.segments.map { it.sequence })
        assertTrue(parsed.segments.all { it.byteRange == null })
    }
}

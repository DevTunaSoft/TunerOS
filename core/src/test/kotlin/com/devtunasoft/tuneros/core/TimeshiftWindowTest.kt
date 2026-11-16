package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeshiftWindowTest {
    private fun segment(sequence: Long, seconds: Double = 2.0, bytes: Long = 100) =
        TimeshiftWindow.Segment(sequence, seconds, bytes, "seg-$sequence.ts")

    @Test fun theOldestSegmentsLeaveThePastTheSegmentLimit() {
        val window = TimeshiftWindow(maxSegments = 3, maxBytes = 10_000)
        listOf(0L, 1L, 2L).forEach { window.add(segment(it)) }
        val evicted = window.add(segment(3))
        assertEquals(listOf(0L), evicted.map { it.sequence })
        assertEquals(listOf(1L, 2L, 3L), window.segments().map { it.sequence })
    }

    @Test fun theOldestSegmentsLeaveThePastTheByteLimit() {
        val window = TimeshiftWindow(maxSegments = 100, maxBytes = 250)
        window.add(segment(0, bytes = 100))
        window.add(segment(1, bytes = 100))
        val evicted = window.add(segment(2, bytes = 100))
        assertEquals(listOf(0L), evicted.map { it.sequence })
        assertEquals(200L, window.bytes)
    }

    @Test fun aSegmentLargerThanTheWholeBudgetIsRefusedAndTheWindowIsUnchanged() {
        val window = TimeshiftWindow(maxSegments = 10, maxBytes = 150)
        window.add(segment(0, bytes = 100))
        val refused = window.add(segment(1, bytes = 151))
        assertEquals(listOf(1L), refused.map { it.sequence })
        assertEquals(listOf(0L), window.segments().map { it.sequence })
        assertEquals(100L, window.bytes)
    }

    @Test fun sequenceNumbersMustIncrease() {
        val window = TimeshiftWindow(maxSegments = 3, maxBytes = 10_000)
        window.add(segment(5))
        assertFailsWith<IllegalArgumentException> { window.add(segment(5)) }
        assertFailsWith<IllegalArgumentException> { window.add(segment(4)) }
    }

    @Test fun aSegmentNamesOnlyAFileInTheWindowDirectory() {
        assertFailsWith<IllegalArgumentException> { TimeshiftWindow.Segment(0, 2.0, 1, "../escape.ts") }
        assertFailsWith<IllegalArgumentException> { TimeshiftWindow.Segment(0, 2.0, 1, "") }
        assertFailsWith<IllegalArgumentException> { TimeshiftWindow.Segment(0, 0.0, 1, "a.ts") }
    }

    @Test fun thePlaylistListsTheWindowInOrderFromItsFirstSequence() {
        val window = TimeshiftWindow(maxSegments = 2, maxBytes = 10_000)
        listOf(0L, 1L, 2L).forEach { window.add(segment(it, seconds = 2.0)) }
        val lines = window.playlist().lines()
        assertEquals("#EXTM3U", lines[0])
        assertTrue(lines.contains("#EXT-X-MEDIA-SEQUENCE:1"), "the first retained segment is sequence 1")
        val body = lines.filter { it.startsWith("#EXTINF") || it.startsWith("seg-") }
        assertEquals(listOf("#EXTINF:2.000,", "seg-1.ts", "#EXTINF:2.000,", "seg-2.ts"), body)
        assertTrue(lines.none { it == "#EXT-X-ENDLIST" }, "a live window does not end")
    }

    @Test fun theTargetDurationRoundsTheLongestSegmentUp() {
        val window = TimeshiftWindow(maxSegments = 5, maxBytes = 10_000)
        window.add(segment(0, seconds = 1.2))
        window.add(segment(1, seconds = 2.0))
        assertTrue(window.playlist().contains("#EXT-X-TARGETDURATION:2\n"))
        window.add(segment(2, seconds = 2.1))
        assertTrue(window.playlist().contains("#EXT-X-TARGETDURATION:3\n"))
    }

    @Test fun anEmptyWindowHasAPlaylistHeaderAndNoSegments() {
        val playlist = TimeshiftWindow(maxSegments = 2, maxBytes = 100).playlist()
        assertTrue(playlist.startsWith("#EXTM3U\n"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:1\n"))
        assertTrue(!playlist.contains("EXTINF"), "an empty window lists no segment")
    }

    @Test fun clearReturnsEveryFileAndEmptiesTheWindow() {
        val window = TimeshiftWindow(maxSegments = 5, maxBytes = 10_000)
        listOf(0L, 1L).forEach { window.add(segment(it)) }
        assertEquals(listOf("seg-0.ts", "seg-1.ts"), window.clear())
        assertEquals(0, window.size)
        assertEquals(0L, window.bytes)
    }

    @Test fun aDiscontinuousSegmentIsMarkedBeforeItsEntry() {
        val window = TimeshiftWindow(maxSegments = 5, maxBytes = 10_000)
        window.add(segment(0))
        window.add(TimeshiftWindow.Segment(1, 2.0, 100, "seg-1.ts", discontinuity = true))
        val playlist = window.playlist()
        assertTrue(playlist.contains("#EXT-X-DISCONTINUITY\n#EXTINF:2.000,\nseg-1.ts\n"), playlist)
        assertEquals(1, playlist.split("#EXT-X-DISCONTINUITY").size - 1, "only the marked segment is a discontinuity")
    }
}

package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial coverage for padded DVR capture windows at the edge of Long.
 *
 * A guide timestamp plus a user padding value can leave the Long range. The capture window must stay ordered and
 * conflict detection must never silently miss an overlap: saturation at the Long bounds is fail-closed because it
 * can only report extra conflicts, never drop a real one. Ordinary, in-range padding is unaffected.
 *
 * All URLs are synthetic canaries; nothing here contacts a provider.
 */
class RecordingWindowOverflowTest {
    private val canaryUrl = "https://example.invalid/live?token=CANARY_RECORDING_WINDOW_4921"

    private fun request(
        id: String,
        start: Long,
        end: Long,
        startPadding: Int = 0,
        endPadding: Int = 0,
    ): RecordingRequest = RecordingRequest(
        id = id,
        channelId = "channel-$id",
        title = "Title $id",
        streamUrl = canaryUrl,
        startEpochSeconds = start,
        endEpochSeconds = end,
        startPaddingSeconds = startPadding,
        endPaddingSeconds = endPadding,
    )

    @Test
    fun startPaddingBelowLongMinKeepsTheWindowOrderedAndReflexive() {
        val padded = request("start", Long.MIN_VALUE + 1, Long.MIN_VALUE + 3, startPadding = 4)
        val earliest = request("earliest", Long.MIN_VALUE, Long.MIN_VALUE + 1)

        assertEquals(Long.MIN_VALUE, padded.captureStartEpochSeconds)
        assertEquals(Long.MIN_VALUE + 3, padded.captureEndEpochSeconds)
        assertTrue(padded.captureStartEpochSeconds <= padded.captureEndEpochSeconds)
        assertTrue(RecordingPolicy.conflicts(padded, padded))
        assertTrue(RecordingPolicy.conflicts(padded, earliest))
        assertTrue(RecordingPolicy.conflicts(earliest, padded))
    }

    @Test
    fun endPaddingAboveLongMaxKeepsTheWindowOrderedAndReflexive() {
        val padded = request("end", Long.MAX_VALUE - 3, Long.MAX_VALUE - 1, endPadding = 4)
        val latest = request("latest", Long.MAX_VALUE - 1, Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE - 3, padded.captureStartEpochSeconds)
        assertEquals(Long.MAX_VALUE, padded.captureEndEpochSeconds)
        assertTrue(padded.captureStartEpochSeconds <= padded.captureEndEpochSeconds)
        assertTrue(RecordingPolicy.conflicts(padded, padded))
        assertTrue(RecordingPolicy.conflicts(padded, latest))
        assertTrue(RecordingPolicy.conflicts(latest, padded))
    }

    @Test
    fun paddingExactlyAtTheLongBoundaryKeepsExactArithmetic() {
        val lower = request("lower", Long.MIN_VALUE + 8, Long.MIN_VALUE + 9, startPadding = 8)
        val upper = request("upper", Long.MAX_VALUE - 9, Long.MAX_VALUE - 8, endPadding = 8)

        assertEquals(Long.MIN_VALUE, lower.captureStartEpochSeconds)
        assertEquals(Long.MIN_VALUE + 9, lower.captureEndEpochSeconds)
        assertEquals(Long.MAX_VALUE - 9, upper.captureStartEpochSeconds)
        assertEquals(Long.MAX_VALUE, upper.captureEndEpochSeconds)
        assertTrue(RecordingPolicy.conflicts(lower, lower))
        assertTrue(RecordingPolicy.conflicts(upper, upper))
    }

    @Test
    fun extremeGuideTimesSurviveProgramConversionWithoutLeakingTheCanary() {
        val channel = Channel("ch", "Channel", canaryUrl)
        val first = GuideProgram("guide", "Early", Long.MIN_VALUE, Long.MIN_VALUE + 1)
        val last = GuideProgram("guide", "Late", Long.MAX_VALUE - 1, Long.MAX_VALUE)

        val early = RecordingPolicy.fromProgram(channel, first, "early", startPaddingSeconds = Int.MAX_VALUE)
        val late = RecordingPolicy.fromProgram(channel, last, "late", endPaddingSeconds = Int.MAX_VALUE)

        assertEquals(Long.MIN_VALUE, early.captureStartEpochSeconds)
        assertEquals(Long.MIN_VALUE + 1, early.captureEndEpochSeconds)
        assertEquals(Long.MAX_VALUE - 1, late.captureStartEpochSeconds)
        assertEquals(Long.MAX_VALUE, late.captureEndEpochSeconds)
        assertTrue(RecordingPolicy.conflicts(early, early))
        assertTrue(RecordingPolicy.conflicts(late, late))
    }
}

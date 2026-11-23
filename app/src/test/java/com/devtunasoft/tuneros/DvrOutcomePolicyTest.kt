package com.devtunasoft.tuneros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic finalization decisions for the app-layer DVR path.
 *
 * These tests exercise the exact policy the foreground service calls when it settles a capture:
 * STOP / onDestroy (cancelled), a zero-byte stop, a partially written file, and a sink that does
 * not acknowledge the engine's byte count. No network, Android framework or user data is used.
 */
class DvrOutcomePolicyTest {
    @Test
    fun aFinalizedCaptureWithBytesStaysCompleted() {
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("COMPLETED", 4096L, true), cancelled = false, sinkBytes = 4096L,
        )
        assertEquals("COMPLETED", decision.state)
        assertTrue(decision.finalize)
        assertFalse(decision.discardEmpty)
        assertNull(decision.failureReason)
    }

    @Test
    fun aZeroByteCompletedStopIsFailedAndNeverFinalized() {
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("COMPLETED", 0L, true), cancelled = false, sinkBytes = 0L,
        )
        assertEquals("FAILED", decision.state)
        assertFalse(decision.finalize)
        assertTrue(decision.discardEmpty)
        assertNotNull(decision.failureReason)
    }

    @Test
    fun stopAfterEngineSuccessCannotPromoteAPartialCapture() {
        // Interruption: STOP or onDestroy lands after the copy loop returned a COMPLETED outcome.
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("COMPLETED", 8192L, true), cancelled = true, sinkBytes = 8192L,
        )
        assertEquals("CANCELLED", decision.state)
        assertFalse(decision.finalize)
        assertFalse(decision.discardEmpty)
        assertNull(decision.failureReason)
    }

    @Test
    fun stopAfterPartialWriteKeepsThePartialFile() {
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("PARTIAL", 512L, false, "Storage reserve reached"),
            cancelled = true,
            sinkBytes = 512L,
        )
        assertEquals("CANCELLED", decision.state)
        assertFalse(decision.finalize)
        assertFalse(decision.discardEmpty)
    }

    @Test
    fun aPartiallyWrittenCaptureIsNeverFinalized() {
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("PARTIAL", 777L, true, "Stream ended before scheduled stop"),
            cancelled = false,
            sinkBytes = 777L,
        )
        assertEquals("PARTIAL", decision.state)
        assertFalse(decision.finalize)
        assertFalse(decision.discardEmpty)
        assertEquals("Stream ended before scheduled stop", decision.failureReason)
    }

    @Test
    fun unreportedSinkBytesCannotJustifyCompletion() {
        // Fault injection: the engine claims a clean byte count but the owned output received nothing.
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("COMPLETED", 4096L, true), cancelled = false, sinkBytes = 0L,
        )
        assertEquals("FAILED", decision.state)
        assertFalse(decision.finalize)
        assertTrue(decision.discardEmpty)
    }

    @Test
    fun aCompletedRequestWithoutFinalizationIsDowngraded() {
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("COMPLETED", 64L, false), cancelled = false, sinkBytes = 64L,
        )
        assertEquals("PARTIAL", decision.state)
        assertFalse(decision.finalize)
        assertNotNull(decision.failureReason)
    }

    @Test
    fun anAlreadyFailedCaptureIsNotRewritten() {
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("FAILED", 0L, false, "Unsupported format"),
            cancelled = false,
            sinkBytes = 0L,
        )
        assertEquals("FAILED", decision.state)
        assertFalse(decision.finalize)
        assertEquals("Unsupported format", decision.failureReason)
    }

    @Test
    fun singleByteIsEnoughToFinalizeAtTheBoundary() {
        val decision = RecordingOutcomePolicy.settle(
            RecordingOutcome("COMPLETED", 1L, true), cancelled = false, sinkBytes = 1L,
        )
        assertEquals("COMPLETED", decision.state)
        assertTrue(decision.finalize)
    }

    @Test
    fun negativeByteCountsAreRejected() {
        try {
            RecordingOutcomePolicy.settle(
                RecordingOutcome("COMPLETED", -1L, true), cancelled = false, sinkBytes = 0L,
            )
            fail("Negative engine byte counts must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Negative reported bytes"))
        }
        try {
            RecordingOutcomePolicy.settle(
                RecordingOutcome("COMPLETED", 1L, true), cancelled = false, sinkBytes = -1L,
            )
            fail("Negative sink byte counts must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Negative sink bytes"))
        }
    }
}

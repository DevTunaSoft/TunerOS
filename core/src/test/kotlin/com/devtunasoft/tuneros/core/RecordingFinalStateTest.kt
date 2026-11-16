package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordingFinalStateTest {
    @Test fun aCompletedCaptureWithBytesStaysCompleted() {
        assertEquals(RecordingState.COMPLETED, RecordingPolicy.finalState(RecordingState.COMPLETED, 1L))
        assertEquals(RecordingState.COMPLETED, RecordingPolicy.finalState(RecordingState.COMPLETED, 47_270L))
    }

    @Test fun aCompletedCaptureWithNoBytesBecomesAFailure() {
        // A late start, or a first read that returns after the stop, must not leave a playable empty file.
        assertEquals(RecordingState.FAILED, RecordingPolicy.finalState(RecordingState.COMPLETED, 0L))
    }

    @Test fun otherStatesAreNotRewritten() {
        assertEquals(RecordingState.PARTIAL, RecordingPolicy.finalState(RecordingState.PARTIAL, 0L))
        assertEquals(RecordingState.FAILED, RecordingPolicy.finalState(RecordingState.FAILED, 0L))
        assertEquals(RecordingState.CANCELLED, RecordingPolicy.finalState(RecordingState.CANCELLED, 0L))
    }

    @Test fun negativeByteCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            RecordingPolicy.finalState(RecordingState.COMPLETED, -1L)
        }
    }
}

package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage of recording final-state and outcome policy.
 *
 * Complements [RecordingFinalStateTest] (single-call COMPLETED/zero-byte cases),
 * [StreamRecorderTest] (byte-copy cancellation), [CoreTest] (model validation basics)
 * and [RecordingWorkerLifecycleTest] (shutdown ordering) without repeating them.
 * Every URL here is a synthetic canary on the reserved .invalid domain.
 */
class RecordingFinalStateAdversarialTest {
    private data class OutcomeCase(
        val requested: RecordingState,
        val bytes: Long,
        val expected: RecordingState,
    )

    private val terminal = setOf(
        RecordingState.COMPLETED,
        RecordingState.PARTIAL,
        RecordingState.FAILED,
        RecordingState.CANCELLED,
    )

    private val outcomes = listOf(
        OutcomeCase(RecordingState.COMPLETED, 0L, RecordingState.FAILED),
        OutcomeCase(RecordingState.COMPLETED, 1L, RecordingState.COMPLETED),
        OutcomeCase(RecordingState.COMPLETED, Long.MAX_VALUE, RecordingState.COMPLETED),
        OutcomeCase(RecordingState.PARTIAL, 0L, RecordingState.PARTIAL),
        OutcomeCase(RecordingState.PARTIAL, 1L, RecordingState.PARTIAL),
        OutcomeCase(RecordingState.PARTIAL, Long.MAX_VALUE, RecordingState.PARTIAL),
        OutcomeCase(RecordingState.FAILED, 0L, RecordingState.FAILED),
        OutcomeCase(RecordingState.FAILED, 1L, RecordingState.FAILED),
        OutcomeCase(RecordingState.FAILED, Long.MAX_VALUE, RecordingState.FAILED),
        OutcomeCase(RecordingState.CANCELLED, 0L, RecordingState.CANCELLED),
        OutcomeCase(RecordingState.CANCELLED, 1L, RecordingState.CANCELLED),
        OutcomeCase(RecordingState.CANCELLED, Long.MAX_VALUE, RecordingState.CANCELLED),
    )

    @Test
    fun everyTerminalOutcomeMatchesItsAdversarialExpectationAndStaysTerminal() {
        for (case in outcomes) {
            val actual = RecordingPolicy.finalState(case.requested, case.bytes)
            assertEquals(case.expected, actual, "requested=${case.requested} bytes=${case.bytes}")
            assertTrue(actual in terminal, "a settled recording must be terminal: ${case.requested}/${case.bytes}")
        }
    }

    @Test
    fun settlingTheSameOutcomeTwiceIsIdempotent() {
        for (case in outcomes) {
            val once = RecordingPolicy.finalState(case.requested, case.bytes)
            val twice = RecordingPolicy.finalState(once, case.bytes)
            assertEquals(once, twice, "re-settling changed ${case.requested}/${case.bytes}")
        }
    }

    @Test
    fun cancellationNeverBecomesPartialOrCompleteEvenAfterBytesWereWritten() {
        val cancelled = RecordingPolicy.finalState(RecordingState.CANCELLED, 8_192L)
        assertEquals(RecordingState.CANCELLED, cancelled)
        assertEquals(RecordingState.CANCELLED, RecordingPolicy.finalState(cancelled, 8_192L))
        assertFalse(cancelled == RecordingState.PARTIAL || cancelled == RecordingState.COMPLETED)
    }

    @Test
    fun aPartialCaptureIsNeverPromotedToCompleteAtAnyByteCount() {
        for (bytes in listOf(0L, 1L, 1_024L, Long.MAX_VALUE)) {
            val partial = RecordingPolicy.finalState(RecordingState.PARTIAL, bytes)
            assertEquals(RecordingState.PARTIAL, partial)
            assertEquals(RecordingState.PARTIAL, RecordingPolicy.finalState(partial, bytes))
        }
    }

    @Test
    fun zeroByteCompletionFailsOnceAndRemainsFailed() {
        val failed = RecordingPolicy.finalState(RecordingState.COMPLETED, 0L)
        assertEquals(RecordingState.FAILED, failed)
        assertEquals(RecordingState.FAILED, RecordingPolicy.finalState(failed, 0L))
        assertEquals(RecordingState.FAILED, RecordingPolicy.finalState(failed, Long.MAX_VALUE))
    }

    @Test
    fun negativeByteCountsAreRejectedForEveryRequestedState() {
        for (requested in RecordingState.values()) {
            for (invalid in listOf(-1L, Long.MIN_VALUE)) {
                assertFailsWith<IllegalArgumentException> {
                    RecordingPolicy.finalState(requested, invalid)
                }
            }
        }
    }

    @Test
    fun settlementDoesNotDependOnCallHistory() {
        val states = RecordingState.values()
        val byteSteps = longArrayOf(0L, 1L, 7L, 1_024L, Long.MAX_VALUE)
        val iterations = 4_096
        fun expected(state: RecordingState, bytes: Long): RecordingState =
            if (state == RecordingState.COMPLETED && bytes == 0L) RecordingState.FAILED else state

        for (i in 0 until iterations) {
            val state = states[i % states.size]
            val bytes = byteSteps[i % byteSteps.size]
            assertEquals(expected(state, bytes), RecordingPolicy.finalState(state, bytes))
        }
        for (i in iterations - 1 downTo 0) {
            val state = states[i % states.size]
            val bytes = byteSteps[i % byteSteps.size]
            assertEquals(expected(state, bytes), RecordingPolicy.finalState(state, bytes))
        }
    }

    @Test
    fun coreRecordingModelsDoNotRetainCollectionsMapsOrArrays() {
        val types = listOf(
            RecordingRequest::class.java,
            RecordingProgress::class.java,
            StreamRecorder.Result::class.java,
            StreamRecorder::class.java,
        )
        for (type in types) {
            for (field in type.declaredFields) {
                val fieldType = field.type
                val retained = java.util.Collection::class.java.isAssignableFrom(fieldType) ||
                    java.util.Map::class.java.isAssignableFrom(fieldType) ||
                    fieldType.isArray
                assertFalse(retained, "${type.simpleName}.${field.name} would retain unbounded state")
            }
        }
    }

    @Test
    fun validationFailuresNeverEchoCanaryUrlsOrTokens() {
        val canary = "CANARY_RECORDING_TOKEN_9137"
        val safeUrl = "https://example.invalid/live?token=$canary"
        val invalidRequests = listOf<() -> Unit>(
            { RecordingRequest("", "channel", "title", safeUrl, 1L, 2L) },
            { RecordingRequest("id", "", "title", safeUrl, 1L, 2L) },
            { RecordingRequest("id", "channel", "", safeUrl, 1L, 2L) },
            { RecordingRequest("id", "channel", "title", "ftp://example.invalid/live?token=$canary", 1L, 2L) },
            { RecordingRequest("id", "channel", "title", safeUrl, 2L, 2L) },
            { RecordingRequest("id", "channel", "title", safeUrl, 1L, 2L, startPaddingSeconds = -1) },
            { RecordingRequest("id", "channel", "title", safeUrl, 1L, 2L, endPaddingSeconds = -1) },
        )
        for (build in invalidRequests) {
            val error = assertFailsWith<IllegalArgumentException> { build() }
            val message = error.message.orEmpty()
            assertFalse(message.contains(canary), "validation must not echo the synthetic token")
            assertFalse(message.contains("example.invalid"), "validation must not echo the synthetic URL")
        }
    }
}

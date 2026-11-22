package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Model test of the timeshift window. A naive list model applies the same rules: a segment larger than the whole byte
 * budget is returned at once and never kept, and otherwise the oldest segments leave while the window holds too many
 * segments or too many bytes. After every add, the window must match the model, and it must never exceed its limits.
 * Every iteration is seeded, so a failure reproduces from the iteration number.
 */
class TimeshiftWindowModelTest {
    @Test fun theWindowMatchesANaiveModelOverRandomAdds() {
        val random = Random(20261122)
        var evictions = 0
        var refusals = 0
        repeat(300) { iteration ->
            val maxSegments = random.nextInt(1, 8)
            val maxBytes = random.nextLong(1L, 2_000L)
            val window = TimeshiftWindow(maxSegments, maxBytes)
            val model = ArrayList<TimeshiftWindow.Segment>()
            var sequence = random.nextLong(0L, 10L)
            repeat(random.nextInt(0, 40)) {
                sequence += random.nextLong(1L, 3L)
                val segment = TimeshiftWindow.Segment(
                    sequence = sequence,
                    durationSeconds = random.nextDouble(0.5, 6.0),
                    bytes = random.nextLong(0L, 1_500L),
                    fileName = "seg-$sequence.ts",
                )
                val expectedLeft = ArrayList<TimeshiftWindow.Segment>()
                if (segment.bytes > maxBytes) {
                    expectedLeft += segment
                    refusals++
                } else {
                    model += segment
                    while (model.size > maxSegments || model.sumOf { it.bytes } > maxBytes) {
                        expectedLeft += model.removeAt(0)
                        evictions++
                    }
                }
                val left = window.add(segment)
                assertEquals(expectedLeft, left, "iteration $iteration")
                assertEquals(model, window.segments(), "iteration $iteration")
                assertEquals(model.sumOf { it.bytes }, window.bytes, "iteration $iteration")
                assertTrue(window.size <= maxSegments && window.bytes <= maxBytes, "iteration $iteration exceeds a limit")
            }
        }
        // Both paths must be exercised, or the model could match without ever evicting or refusing.
        assertTrue(evictions >= 50 && refusals >= 50, "evictions $evictions, refusals $refusals")
    }
}

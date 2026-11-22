package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deterministic fuzzing of the DASH sidx parser. Random bytes and mutated valid index boxes must always produce an
 * outcome, never an exception. A parsed index must have a positive timescale, at least one reference, positive sizes and
 * durations, and byte ranges that move forward through the file. Every iteration is seeded, so a failure reproduces from
 * the iteration number.
 */
class DashSegmentIndexFuzzTest {
    private val valid = SidxBox.build(
        version = 0,
        timescale = 1_000,
        firstOffset = 25,
        references = listOf(500L to 2_000L, 700L to 2_000L, 600L to 3_000L),
    )

    private fun outcomeOf(bytes: ByteArray): DashSegmentIndex.Outcome =
        DashSegmentIndex.parse(bytes, HlsByteRange(length = bytes.size.toLong(), offset = 0))

    private fun checkOutcome(outcome: DashSegmentIndex.Outcome, iteration: Int): Boolean {
        if (outcome !is DashSegmentIndex.Outcome.Parsed) return false
        assertTrue(outcome.timescale > 0, "iteration $iteration has a zero timescale")
        assertTrue(outcome.references.isNotEmpty(), "iteration $iteration has no references")
        assertTrue(
            outcome.references.all { it.range.length > 0 && it.durationTicks > 0 },
            "iteration $iteration has an empty reference",
        )
        assertTrue(
            outcome.references.zipWithNext().all { (earlier, later) -> later.range.offset > earlier.range.offset },
            "iteration $iteration has byte ranges that do not move forward",
        )
        return true
    }

    @Test fun randomBytesAlwaysGiveAnOutcomeAndParsedIndexesStayWellFormed() {
        // An exception from the parser would fail the test here, so this loop only has to check outcomes that parse.
        val random = Random(20261122)
        repeat(2_000) { iteration ->
            checkOutcome(outcomeOf(random.nextBytes(random.nextInt(0, 120))), iteration)
        }
    }

    @Test fun mutatedValidIndexesAlwaysGiveAnOutcomeAndParsedIndexesStayWellFormed() {
        val random = Random(4343)
        var accepted = 0
        repeat(2_000) { iteration ->
            val bytes = valid.copyOf()
            repeat(random.nextInt(1, 4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            if (checkOutcome(outcomeOf(bytes), iteration)) accepted++
        }
        // Mutating sizes and durations often keeps a valid box, so a useful share must parse to be checked at all.
        assertTrue(accepted >= 50, "only $accepted of 2000 mutated indexes parsed")
    }
}

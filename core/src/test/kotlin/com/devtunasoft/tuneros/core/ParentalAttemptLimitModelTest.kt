package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Model-based check of the parental PIN attempt limit. A separate model of the rules runs beside the implementation over
 * seeded random sequences of correct and wrong entries at increasing times. A restart is simulated by rebuilding the limit
 * from its saved failure count and lock end, which must then continue exactly as the original does, because those two
 * values are everything that is stored.
 */
class ParentalAttemptLimitModelTest {
    private class Model(private val maxFailures: Int, private val lockMillis: Long) {
        private var streak = 0
        private var lockedUntil = 0L

        fun expect(correct: Boolean, now: Long): ParentalAttemptLimit.Outcome {
            if (now < lockedUntil) return ParentalAttemptLimit.Outcome.Locked(lockedUntil)
            if (correct) {
                streak = 0
                return ParentalAttemptLimit.Outcome.Accepted
            }
            streak++
            if (streak == maxFailures) {
                streak = 0
                lockedUntil = now + lockMillis
                return ParentalAttemptLimit.Outcome.Locked(lockedUntil)
            }
            return ParentalAttemptLimit.Outcome.Rejected(maxFailures - streak)
        }
    }

    @Test fun theLimitFollowsTheModelOverRandomSequences() {
        for (seed in 0 until 200) {
            val random = Random(seed)
            val maxFailures = random.nextInt(1, 6)
            val lockMillis = random.nextLong(1L, 120_000L)
            val limit = ParentalAttemptLimit(maxFailures, lockMillis)
            val model = Model(maxFailures, lockMillis)
            var now = 0L
            for (step in 0 until 400) {
                now += random.nextLong(0L, 90_000L)
                val correct = random.nextInt(4) == 0
                assertEquals(model.expect(correct, now), limit.attempt(correct, now), "seed $seed step $step")
            }
        }
    }

    @Test fun aRestoredLimitContinuesExactlyLikeTheOriginal() {
        for (seed in 0 until 200) {
            val random = Random(10_000 + seed)
            val maxFailures = random.nextInt(1, 6)
            val lockMillis = random.nextLong(1L, 120_000L)
            val original = ParentalAttemptLimit(maxFailures, lockMillis)
            var restored = ParentalAttemptLimit(maxFailures, lockMillis)
            var now = 0L
            for (step in 0 until 400) {
                now += random.nextLong(0L, 90_000L)
                val correct = random.nextInt(4) == 0
                assertEquals(original.attempt(correct, now), restored.attempt(correct, now), "seed $seed step $step")
                if (random.nextInt(5) == 0) {
                    restored = ParentalAttemptLimit(
                        maxFailures, lockMillis, original.failures, original.lockedUntilMillis,
                    )
                }
            }
        }
    }
}

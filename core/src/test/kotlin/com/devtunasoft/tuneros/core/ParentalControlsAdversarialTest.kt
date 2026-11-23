package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage of the core parental-control seams. Every value is synthetic scratch material: no
 * provider address, real PIN or credential appears here. The companion [ParentalControlsTest] covers the ordinary matrix and
 * [ParentalAttemptLimitModelTest] the seeded model; this class covers the boundaries those suites do not exercise.
 */
class ParentalControlsAdversarialTest {
    private val scratchPin = "1357"
    private val saltA = ByteArray(ParentalPin.SALT_BYTES) { (it * 3 + 1).toByte() }
    private val saltB = ByteArray(ParentalPin.SALT_BYTES) { (it * 5 + 2).toByte() }

    @Test
    fun pinValidityRejectsNonAsciiDigitsControlsAndOverlongNumericInput() {
        assertFalse(ParentalPin.isValid("１２３４"))
        assertFalse(ParentalPin.isValid("١٢٣٤"))
        assertFalse(ParentalPin.isValid("1\u0000234"))
        assertFalse(ParentalPin.isValid("1\t234"))
        assertFalse(ParentalPin.isValid("1".repeat(ParentalPin.MAX_DIGITS + 1)))
        assertFalse(ParentalPin.isValid("1".repeat(1_000)))
        assertTrue(ParentalPin.isValid("0".repeat(ParentalPin.MAX_DIGITS)))
    }

    @Test
    fun hashingTheSamePinAndSaltIsDeterministicAndSensitiveToBoth() {
        val first = ParentalPin.hash(scratchPin, saltA)
        val again = ParentalPin.hash(scratchPin, saltA.copyOf())
        val otherSalt = ParentalPin.hash(scratchPin, saltB)
        val otherPin = ParentalPin.hash("1358", saltA)

        assertEquals(ParentalPin.HASH_BYTES, first.size)
        assertTrue(first.contentEquals(again), "the same PIN and salt must produce the same hash")
        assertFalse(first.contentEquals(otherSalt), "a different salt must change the hash")
        assertFalse(first.contentEquals(otherPin), "a different PIN must change the hash")
    }

    @Test
    fun hashFailuresAreClosedAndDoNotEchoSyntheticInput() {
        val marker = "synthetic-trace-7Q"
        val badPin = "$scratchPin$marker"
        val pinError = assertFailsWith<IllegalArgumentException> { ParentalPin.hash(badPin, saltA) }
        assertFalse(pinError.message.orEmpty().contains(marker))
        assertFalse(pinError.message.orEmpty().contains(badPin))

        for (size in listOf(0, 1, ParentalPin.SALT_BYTES - 1, ParentalPin.SALT_BYTES + 1, 64)) {
            val saltError = assertFailsWith<IllegalArgumentException> {
                ParentalPin.hash(scratchPin, ByteArray(size))
            }
            assertEquals("the salt is ${ParentalPin.SALT_BYTES} bytes", saltError.message)
        }
        assertFalse(ParentalPin.matches(badPin, saltA, ParentalPin.hash(scratchPin, saltA)))
    }

    @Test
    fun hashingDelegatesSecretCleanupAndComparisonToTheJdkPrimitives() {
        // Constant time and secret wiping are properties of the JDK primitives, invisible through return values. Pin the
        // compiled call sites so an early-exit byte loop or an uncleaned PBEKeySpec is caught by a deterministic assertion.
        val bytecode = parentalPinBytecode()
        assertTrue(bytecode.contains("MessageDigest") && bytecode.contains("isEqual"))
        assertTrue(bytecode.contains("PBEKeySpec") && bytecode.contains("clearPassword"))
    }

    @Test
    fun matchesRejectsExpectedArraysOfTheWrongLengthWithoutThrowing() {
        val stored = ParentalPin.hash(scratchPin, saltA)
        assertFalse(ParentalPin.matches(scratchPin, saltA, stored.copyOf(ParentalPin.HASH_BYTES - 1)))
        assertFalse(ParentalPin.matches(scratchPin, saltA, stored.copyOf(ParentalPin.HASH_BYTES + 1)))
        assertFalse(ParentalPin.matches(scratchPin, saltA, ByteArray(0)))
        assertTrue(ParentalPin.matches(scratchPin, saltA, stored))
    }

    @Test
    fun attemptsLeftNeverWrapsAndRestoredLocksRemainLockedNearLongMaxValue() {
        val restored = ParentalAttemptLimit(
            maxFailures = 5,
            lockMillis = 60_000L,
            lockedUntilMillis = Long.MAX_VALUE,
        )
        assertTrue(restored.isLocked(Long.MAX_VALUE - 1))
        assertEquals(
            ParentalAttemptLimit.Outcome.Locked(Long.MAX_VALUE),
            restored.attempt(correct = true, nowMillis = Long.MAX_VALUE - 1),
        )
        val counted = ParentalAttemptLimit(maxFailures = 5, lockMillis = 60_000L, failures = 1)
        assertEquals(
            ParentalAttemptLimit.Outcome.Rejected(3),
            counted.attempt(correct = false, nowMillis = Long.MAX_VALUE - 1),
        )
    }

    @Test
    fun lockDeadlineSaturatesAtLongMaxValueInsteadOfWrappingNegative() {
        val limit = ParentalAttemptLimit(maxFailures = 1, lockMillis = 60_000L)
        val now = Long.MAX_VALUE - 10L
        assertEquals(ParentalAttemptLimit.Outcome.Locked(Long.MAX_VALUE), limit.attempt(false, now))
        assertEquals(Long.MAX_VALUE, limit.lockedUntilMillis)
        assertTrue(limit.isLocked(Long.MAX_VALUE - 1))
        assertEquals(
            ParentalAttemptLimit.Outcome.Locked(Long.MAX_VALUE),
            limit.attempt(true, Long.MAX_VALUE - 1),
            "a wrapped deadline must never let the right PIN through",
        )
    }

    @Test
    fun policyNormalizationIsStableAcrossDuplicatesCaseAndSurroundingWhitespace() {
        val original = setOf("  Adult \t", "ADULT", "adult", " News\n")
        val snapshot = original.toSet()
        assertEquals(setOf("adult", "news"), ParentalPolicy.normalizeAll(original))
        assertEquals(snapshot, original, "normalization must not mutate the caller's set")

        var groups = ParentalPolicy.restrict(emptySet(), "  Adult ", restricted = true)
        groups = ParentalPolicy.restrict(groups, "ADULT", restricted = true)
        groups = ParentalPolicy.restrict(groups, "\tadult\n", restricted = true)
        assertEquals(setOf("adult"), groups, "re-adding the same group must stay bounded")
        assertEquals(emptySet<String>(), ParentalPolicy.restrict(groups, "AdUlT", restricted = false))
    }

    @Test
    fun policyRefusalsAreFailClosedAndNeverEchoSyntheticNames() {
        val marker = "synthetic-trace-7Q"
        val blankError = assertFailsWith<IllegalArgumentException> {
            ParentalPolicy.normalizeAll(setOf("adult", " \t\n "))
        }
        assertFalse(blankError.message.orEmpty().contains(marker))

        val full = (1..ParentalPolicy.MAX_RESTRICTED_GROUPS).map { "group$it" }.toSet()
        val extraError = assertFailsWith<IllegalArgumentException> {
            ParentalPolicy.restrict(full, " $marker ", restricted = true)
        }
        assertFalse(extraError.message.orEmpty().contains(marker))
        assertFalse(extraError.message.orEmpty().contains("group1"))
        val listError = assertFailsWith<IllegalArgumentException> {
            ParentalPolicy.normalizeAll(full + marker)
        }
        assertFalse(listError.message.orEmpty().contains(marker))

        // A blank or control-only stored name must never turn an unrestricted group into a pass, and a blank stored name
        // must keep asking for the PIN: malformed policy data may not open the gate.
        assertTrue(ParentalPolicy.requiresPin("  ", setOf(" "), unlocked = false))
        assertTrue(
            ParentalPolicy.requiresPinForRecording(
                channelGroup = null,
                channelKnown = false,
                restrictedGroups = setOf("\u0000"),
                unlocked = false,
            ),
        )
        assertFalse(ParentalPolicy.requiresPin("adult", setOf("adult"), unlocked = true))
    }

    private fun parentalPinBytecode(): String {
        val stream = ParentalPin::class.java.getResourceAsStream("ParentalPin.class")
        assertNotNull(stream, "the compiled ParentalPin class must be on the test classpath")
        return stream.use { String(it.readBytes(), Charsets.ISO_8859_1) }
    }
}

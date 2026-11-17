package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParentalControlsTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun aPinIsFourToEightDecimalDigits() {
        assertFalse(ParentalPin.isValid(""))
        assertFalse(ParentalPin.isValid("123"))
        assertTrue(ParentalPin.isValid("1234"))
        assertTrue(ParentalPin.isValid("12345678"))
        assertFalse(ParentalPin.isValid("123456789"))
        assertFalse(ParentalPin.isValid("12a4"))
        assertFalse(ParentalPin.isValid("12 4"))
    }

    @Test fun theRightPinMatchesItsSaltedHashAndNothingElseDoes() {
        val stored = ParentalPin.hash("1234", salt)
        assertEquals(ParentalPin.HASH_BYTES, stored.size)
        assertTrue(ParentalPin.matches("1234", salt, stored))
        assertFalse(ParentalPin.matches("1235", salt, stored))
        assertFalse(ParentalPin.matches("1234", ByteArray(16) { 9 }, stored), "the salt is part of the hash")
        assertFalse(ParentalPin.matches("12", salt, stored), "an invalid PIN never matches")
    }

    @Test fun aPinThatIsNotValidIsNotHashed() {
        assertFailsWith<IllegalArgumentException> { ParentalPin.hash("12", salt) }
        assertFailsWith<IllegalArgumentException> { ParentalPin.hash("1234", ByteArray(8)) }
    }

    @Test fun wrongAttemptsCountDownThenLockForAMinute() {
        val limit = ParentalAttemptLimit(maxFailures = 5, lockMillis = 60_000L)
        assertEquals(ParentalAttemptLimit.Outcome.Rejected(4), limit.attempt(correct = false, nowMillis = 1_000L))
        assertEquals(ParentalAttemptLimit.Outcome.Rejected(3), limit.attempt(correct = false, nowMillis = 2_000L))
        assertEquals(ParentalAttemptLimit.Outcome.Rejected(2), limit.attempt(correct = false, nowMillis = 3_000L))
        assertEquals(ParentalAttemptLimit.Outcome.Rejected(1), limit.attempt(correct = false, nowMillis = 4_000L))
        assertEquals(ParentalAttemptLimit.Outcome.Locked(65_000L), limit.attempt(correct = false, nowMillis = 5_000L))
        assertTrue(limit.isLocked(64_999L))
        assertEquals(
            ParentalAttemptLimit.Outcome.Locked(65_000L),
            limit.attempt(correct = true, nowMillis = 30_000L),
            "a locked gate refuses even the right PIN",
        )
        assertEquals(ParentalAttemptLimit.Outcome.Accepted, limit.attempt(correct = true, nowMillis = 65_000L))
    }

    @Test fun aCorrectPinClearsTheFailuresSoEarlierMistakesDoNotAccumulate() {
        val limit = ParentalAttemptLimit(maxFailures = 3, lockMillis = 10_000L)
        limit.attempt(correct = false, nowMillis = 0L)
        limit.attempt(correct = false, nowMillis = 0L)
        assertEquals(ParentalAttemptLimit.Outcome.Accepted, limit.attempt(correct = true, nowMillis = 0L))
        assertEquals(ParentalAttemptLimit.Outcome.Rejected(2), limit.attempt(correct = false, nowMillis = 0L))
    }

    @Test fun theAttemptLimitRejectsImpossibleSettings() {
        assertFailsWith<IllegalArgumentException> { ParentalAttemptLimit(maxFailures = 0, lockMillis = 1L) }
        assertFailsWith<IllegalArgumentException> { ParentalAttemptLimit(maxFailures = 1, lockMillis = 0L) }
        assertFailsWith<IllegalArgumentException> { ParentalAttemptLimit(maxFailures = 5, failures = 5) }
        assertFailsWith<IllegalArgumentException> { ParentalAttemptLimit(maxFailures = 5, lockedUntilMillis = -1L) }
    }

    @Test fun aRestoredLimitContinuesFromItsSavedCountAndItsSavedLock() {
        val counted = ParentalAttemptLimit(maxFailures = 5, lockMillis = 60_000L, failures = 4)
        assertEquals(ParentalAttemptLimit.Outcome.Locked(65_000L), counted.attempt(correct = false, nowMillis = 5_000L))

        val locked = ParentalAttemptLimit(maxFailures = 5, lockMillis = 60_000L, lockedUntilMillis = 65_000L)
        assertTrue(locked.isLocked(10_000L))
        assertEquals(ParentalAttemptLimit.Outcome.Locked(65_000L), locked.attempt(correct = true, nowMillis = 10_000L))
        assertEquals(ParentalAttemptLimit.Outcome.Accepted, locked.attempt(correct = true, nowMillis = 65_000L))
    }

    @Test fun aRestrictedGroupNeedsThePinUntilTheSessionUnlocksIt() {
        val restricted = setOf("Adult", "News")
        assertTrue(ParentalPolicy.requiresPin("adult", restricted, unlocked = false))
        assertTrue(ParentalPolicy.requiresPin("  NEWS ", restricted, unlocked = false))
        assertFalse(ParentalPolicy.requiresPin("Sports", restricted, unlocked = false))
        assertFalse(ParentalPolicy.requiresPin(null, restricted, unlocked = false))
        assertFalse(ParentalPolicy.requiresPin("Adult", restricted, unlocked = true))
    }

    @Test fun restrictingAndReleasingAGroupStoresItsNormalizedNameAndStaysBounded() {
        var groups = ParentalPolicy.restrict(emptySet(), " Adult ", restricted = true)
        assertEquals(setOf("adult"), groups)
        groups = ParentalPolicy.restrict(groups, "ADULT", restricted = false)
        assertEquals(emptySet<String>(), groups)

        val full = (1..ParentalPolicy.MAX_RESTRICTED_GROUPS).map { "group$it" }.toSet()
        assertFailsWith<IllegalArgumentException> { ParentalPolicy.restrict(full, "one more", restricted = true) }
        assertFailsWith<IllegalArgumentException> { ParentalPolicy.restrict(emptySet(), "   ", restricted = true) }
    }

    @Test fun aWholeListIsNormalizedOrRefusedBeforeAnyOfItIsStored() {
        assertEquals(setOf("adult", "news"), ParentalPolicy.normalizeAll(setOf(" Adult ", "adult", "News")))
        assertFailsWith<IllegalArgumentException> { ParentalPolicy.normalizeAll(setOf("adult", "  ")) }
        val most = (1..ParentalPolicy.MAX_RESTRICTED_GROUPS).map { "group$it" }.toSet()
        assertEquals(most, ParentalPolicy.normalizeAll(most))
        assertFailsWith<IllegalArgumentException> { ParentalPolicy.normalizeAll(most + "one more") }
    }

    @Test fun aRestrictedChannelAsksForThePinEvenWhenItsGroupIsNot() {
        val channels = setOf(" News Three ")
        assertTrue(ParentalPolicy.requiresPinForChannel(channelName = "news three", channelGroup = "News", restrictedGroups = emptySet(), restrictedChannels = channels, unlocked = false))
        assertFalse(ParentalPolicy.requiresPinForChannel(channelName = "News Two", channelGroup = "News", restrictedGroups = emptySet(), restrictedChannels = channels, unlocked = false))
        assertFalse(ParentalPolicy.requiresPinForChannel(channelName = "News Three", channelGroup = "News", restrictedGroups = emptySet(), restrictedChannels = channels, unlocked = true))
    }

    @Test fun aRecordingPlaysUnderTheRuleForItsChannel() {
        val restricted = setOf("adult")
        assertTrue(ParentalPolicy.requiresPinForRecording(channelGroup = "Adult", channelKnown = true, restrictedGroups = restricted, unlocked = false))
        assertFalse(ParentalPolicy.requiresPinForRecording(channelGroup = "Sports", channelKnown = true, restrictedGroups = restricted, unlocked = false))
        assertFalse(ParentalPolicy.requiresPinForRecording(channelGroup = null, channelKnown = true, restrictedGroups = restricted, unlocked = false))
    }

    @Test fun aRecordingOfAnUnknownChannelAsksForThePinOnlyWhileAGroupIsRestricted() {
        assertTrue(ParentalPolicy.requiresPinForRecording(channelGroup = null, channelKnown = false, restrictedGroups = setOf("adult"), unlocked = false))
        assertFalse(ParentalPolicy.requiresPinForRecording(channelGroup = null, channelKnown = false, restrictedGroups = emptySet(), unlocked = false))
    }

    @Test fun anUnlockedSessionPlaysEveryRecordingWithoutThePin() {
        assertFalse(ParentalPolicy.requiresPinForRecording(channelGroup = "Adult", channelKnown = true, restrictedGroups = setOf("adult"), unlocked = true))
        assertFalse(ParentalPolicy.requiresPinForRecording(channelGroup = null, channelKnown = false, restrictedGroups = setOf("adult"), unlocked = true))
    }
}

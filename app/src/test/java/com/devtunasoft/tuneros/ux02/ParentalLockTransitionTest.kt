package com.devtunasoft.tuneros.ux02

import com.devtunasoft.tuneros.ParentalControlsStore
import com.devtunasoft.tuneros.core.ParentalAttemptLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-02: the wrong-PIN streak and lock must survive every state transition the
 * store itself offers. All values are synthetic; no real preferences are used.
 */
class ParentalLockTransitionTest {

    @Test
    fun replacingThePinMustNotClearAnActiveLockout() {
        val store = ParentalControlsStore(FakePreferencesContext())
        store.setPin("1234")
        val t0 = System.currentTimeMillis()

        repeat(4) { index -> assertEquals(4 - index, leftOf(store.verify("0000", t0 + index + 1L))) }
        val locking = store.verify("0000", t0 + 5L)
        assertEquals(ParentalAttemptLimit.Outcome.Locked(t0 + 5L + ParentalControlsStore.LOCK_MILLIS), locking)

        // State transition: the set-PIN path writes a different PIN on a locked store.
        store.setPin("5678")

        val duringLock = store.verify("5678", t0 + 6L)
        assertTrue(
            "A PIN change must not bypass the active lockout, got $duringLock",
            duringLock is ParentalAttemptLimit.Outcome.Locked,
        )
    }

    @Test
    fun aFreshStoreStillStartsWithZeroFailures() {
        val store = ParentalControlsStore(FakePreferencesContext())
        store.setPin("1234")
        assertEquals(4, leftOf(store.verify("0000", System.currentTimeMillis())))
    }

    @Test
    fun replacingThePinKeepsTheWrongEntryStreak() {
        val store = ParentalControlsStore(FakePreferencesContext())
        store.setPin("1234")
        val t0 = System.currentTimeMillis()
        repeat(3) { store.verify("0000", t0 + it + 1L) }

        store.setPin("5678")
        assertEquals(1, leftOf(store.verify("0000", t0 + 5L)))
        assertEquals(
            ParentalAttemptLimit.Outcome.Accepted,
            store.verify("5678", t0 + 6L),
        )
    }

    @Test
    fun aLockFromBeforeThePinReplacementStillExpires() {
        val store = ParentalControlsStore(FakePreferencesContext())
        store.setPin("1234")
        val t0 = System.currentTimeMillis()
        repeat(4) { store.verify("0000", t0 + it + 1L) }
        val lock = store.verify("0000", t0 + 5L) as ParentalAttemptLimit.Outcome.Locked
        store.setPin("5678")

        assertEquals(
            ParentalAttemptLimit.Outcome.Accepted,
            store.verify("5678", lock.untilMillis + 1L),
        )
    }

    private fun leftOf(outcome: ParentalAttemptLimit.Outcome?): Int {
        assertTrue("A PIN was expected to be set", outcome is ParentalAttemptLimit.Outcome.Rejected)
        return (outcome as ParentalAttemptLimit.Outcome.Rejected).attemptsLeft
    }
}

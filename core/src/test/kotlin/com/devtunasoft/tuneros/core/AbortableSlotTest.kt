package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbortableSlotTest {
    private class Fake(val name: String) {
        var aborts = 0
    }

    @Test fun abortCurrentAbortsRegisteredValueOnce() {
        val slot = AbortableSlot<Fake> { it.aborts++ }
        val conn = Fake("a")
        assertTrue(slot.register(conn))
        slot.abortCurrent()
        slot.abortCurrent()
        assertEquals(1, conn.aborts)
    }

    @Test fun registerAfterAbortIsAbortedImmediatelyAndRejected() {
        val slot = AbortableSlot<Fake> { it.aborts++ }
        slot.abortCurrent()
        val late = Fake("late")
        assertFalse(slot.register(late))
        assertEquals(1, late.aborts)
    }

    @Test fun releaseOnlyForgetsTheSameValue() {
        val slot = AbortableSlot<Fake> { it.aborts++ }
        val first = Fake("first")
        val second = Fake("second")
        slot.register(first)
        slot.release(second) // not registered: must not forget first
        slot.abortCurrent()
        assertEquals(1, first.aborts)
        assertEquals(0, second.aborts)
    }

    @Test fun releasedValueIsNotAbortedLater() {
        val slot = AbortableSlot<Fake> { it.aborts++ }
        val conn = Fake("done")
        slot.register(conn)
        slot.release(conn)
        slot.abortCurrent()
        assertEquals(0, conn.aborts)
    }

    @Test fun resetAllowsNewWorkAfterAStop() {
        val slot = AbortableSlot<Fake> { it.aborts++ }
        slot.abortCurrent()
        slot.reset()
        val next = Fake("next")
        assertTrue(slot.register(next))
        slot.abortCurrent()
        assertEquals(1, next.aborts)
    }
}

package com.devtunasoft.tuneros.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial coverage of cancellation state in the single-slot abort helper.
 *
 * The slot has no wait/timed-acquire API; its bounded-blocking property is that no slot method
 * holds the monitor while invoking the abort callback. The tests below block that callback on a
 * latch with bounded waits and show that reset/register still complete promptly. Interruption of
 * a callback is checked for the same reason: a thrown InterruptedException must not leave a
 * half-applied cancellation.
 */
class AbortableSlotAdversarialTest {
    private class Probe {
        @Volatile var aborts = 0
    }

    @Test fun cancellationIsOneShotAcrossManyLateRegistrationsUntilReset() {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        slot.abortCurrent() // cancel with nothing registered
        repeat(64) {
            val late = Probe()
            assertFalse(slot.register(late))
            assertEquals(1, late.aborts)
        }
        val fresh = Probe()
        slot.reset()
        assertTrue(slot.register(fresh))
        assertEquals(0, fresh.aborts)
        slot.abortCurrent()
        slot.abortCurrent()
        assertEquals(1, fresh.aborts)
    }

    @Test fun twoThousandAbortResetCyclesDoNotLeakState() {
        val slot = AbortableSlot<Probe> { it.aborts++ }
        val used = ArrayList<Probe>(2_000)
        repeat(2_000) { i ->
            val probe = Probe()
            used += probe
            assertTrue(slot.register(probe))
            if (i % 2 == 0) {
                slot.release(probe)
                assertEquals(0, probe.aborts)
            } else {
                slot.abortCurrent()
                assertEquals(1, probe.aborts)
                slot.reset() // next unit of work must be abortable again
            }
        }
        assertEquals(2_000, used.size)
        assertEquals(1_000, used.sumOf { it.aborts })
        slot.abortCurrent() // nothing registered: no extra abort
        assertEquals(1_000, used.sumOf { it.aborts })
    }

    @Test fun blockingAbortCallbackDoesNotHoldSlotMonitor() {
        val victim = Probe()
        val entered = CountDownLatch(1)
        val allowAbort = CountDownLatch(1)
        val slot = AbortableSlot<Probe> { probe ->
            probe.aborts++
            if (probe === victim) {
                entered.countDown()
                allowAbort.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(slot.register(victim))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val abortTask = executor.submit { slot.abortCurrent() }
            assertTrue(entered.await(2, TimeUnit.SECONDS), "abort callback never started")

            // Cancellation is already latched: a registration racing with the blocked callback
            // must be aborted immediately, and reset/register must not block on that callback.
            val late = Probe()
            assertFalse(slot.register(late))
            assertEquals(1, late.aborts)

            slot.reset()
            val next = Probe()
            assertTrue(slot.register(next))
            slot.release(next)

            allowAbort.countDown()
            abortTask.get(2, TimeUnit.SECONDS)
            assertEquals(1, victim.aborts)
            assertEquals(0, next.aborts)
        } finally {
            allowAbort.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun interruptedAbortCallbackLeavesCancellationConsistent() {
        val victim = Probe()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val slot = AbortableSlot<Probe> { probe ->
            probe.aborts++
            if (probe === victim) {
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (expected: InterruptedException) {
                    Thread.currentThread().interrupt()
                    interrupted.countDown()
                }
            }
        }
        assertTrue(slot.register(victim))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val abortTask = executor.submit { slot.abortCurrent() }
            assertTrue(entered.await(2, TimeUnit.SECONDS), "abort callback never started")
            executor.shutdownNow() // interrupts the callback thread; every wait below is bounded
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "callback did not observe interruption")
            abortTask.get(2, TimeUnit.SECONDS)
            assertEquals(1, victim.aborts)

            val late = Probe()
            assertFalse(slot.register(late))
            assertEquals(1, late.aborts)
            slot.reset()
            val fresh = Probe()
            assertTrue(slot.register(fresh))
            assertEquals(0, fresh.aborts)
        } finally {
            executor.shutdownNow()
        }
    }
}

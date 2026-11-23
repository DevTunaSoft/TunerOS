package com.devtunasoft.tuneros.core

import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage for the cancellation seam that AbortableSlotTest does not exercise.
 *
 * AbortableSlot exposes no wait/waiter API: its only observable seams are register, release, abortCurrent and reset,
 * and the abort callback. "Waiters" below are harness threads held on a latch at that callback seam, and every wait,
 * join or future get has a bounded timeout. No assertion depends on elapsed time or on a particular thread winning a
 * race: each racy scenario has an invariant that holds for either interleaving. All fixtures are synthetic.
 */
private const val WAIT_SECONDS = 10L
private const val THREAD_PREFIX = "abortable-slot-c26-"

private class Probe(val label: String) {
    val aborts = AtomicInteger(0)
}

private class NamedThreads : ThreadFactory {
    private val ids = AtomicInteger(0)

    override fun newThread(r: Runnable): Thread =
        Thread(r, "$THREAD_PREFIX${ids.incrementAndGet()}").apply { isDaemon = true }
}

private fun <T> submitValue(pool: ExecutorService, body: () -> T): Future<T> = pool.submit(Callable(body))

private fun <T> Future<T>.bounded(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

private fun closePool(pool: ExecutorService) {
    pool.shutdownNow()
    assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "test pool did not terminate")
    val leaked = Thread.getAllStackTraces().keys.filter { it.name.startsWith(THREAD_PREFIX) && it.isAlive }
    assertTrue(leaked.isEmpty(), "leaked test threads: ${leaked.map { it.name }}")
}

class AbortableSlotAdversarialTest {

    @Test fun abortCallbackRunsOutsideTheSlotLockSoOtherCallersAreNotBlocked() {
        val pool = Executors.newFixedThreadPool(2, NamedThreads())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackFinished = AtomicBoolean(false)
        val victim = Probe("synthetic-victim")
        val slot = AbortableSlot<Probe> { value ->
            if (value === victim) {
                entered.countDown()
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Interruption is exercised by a separate test; here it only prevents a hung callback.
                }
            }
            callbackFinished.set(true)
            value.aborts.incrementAndGet()
        }
        try {
            assertTrue(slot.register(victim))
            val aborter = submitValue(pool) { slot.abortCurrent(); Unit }
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "abort callback never entered")

            // The callback is still blocked. Other public calls must finish on a different thread.
            val other = Probe("synthetic-other")
            val registerOther = submitValue(pool) { slot.register(other) }
            val releaseOther = submitValue(pool) { slot.release(other); Unit }
            val resetSlot = submitValue(pool) { slot.reset(); Unit }
            assertFalse(registerOther.bounded(), "register blocked behind the abort callback")
            releaseOther.bounded()
            resetSlot.bounded()
            assertEquals(0, victim.aborts.get(), "victim aborted before its callback returned")
            assertEquals(1, other.aborts.get(), "late registration must abort its own value")

            release.countDown()
            aborter.bounded()
            assertTrue(callbackFinished.get())
            assertEquals(1, victim.aborts.get())
            val next = Probe("synthetic-next")
            assertTrue(slot.register(next), "reset did not clear the cancelled state")
            slot.abortCurrent()
            assertEquals(1, next.aborts.get())
        } finally {
            release.countDown()
            closePool(pool)
        }
    }

    @Test fun abortRacingWithRegisterAbortsEachValueExactlyOnceAcrossRepeatedCycles() {
        val pool = Executors.newFixedThreadPool(3, NamedThreads())
        try {
            repeat(200) { iteration ->
                val slot = AbortableSlot<Probe> { it.aborts.incrementAndGet() }
                val raced = Probe("synthetic-race-$iteration")
                val go = CountDownLatch(1)
                val register = submitValue(pool) {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    slot.register(raced)
                }
                val abort = submitValue(pool) {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    slot.abortCurrent()
                    Unit
                }
                go.countDown()
                val registered = register.bounded()
                abort.bounded()
                assertEquals(1, raced.aborts.get(), "iteration $iteration, registered=$registered")
                val late = Probe("synthetic-late-$iteration")
                assertFalse(slot.register(late), "iteration $iteration: cancellation state not latched")
                assertEquals(1, late.aborts.get(), "iteration $iteration")
            }
        } finally {
            closePool(pool)
        }
    }

    @Test fun twoConcurrentAbortCurrentCallsNeverAbortTheRegisteredValueTwice() {
        val pool = Executors.newFixedThreadPool(3, NamedThreads())
        try {
            repeat(200) { iteration ->
                val slot = AbortableSlot<Probe> { it.aborts.incrementAndGet() }
                val victim = Probe("synthetic-double-$iteration")
                assertTrue(slot.register(victim))
                val go = CountDownLatch(1)
                val first = submitValue(pool) {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    slot.abortCurrent()
                    Unit
                }
                val second = submitValue(pool) {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    slot.abortCurrent()
                    Unit
                }
                go.countDown()
                first.bounded()
                second.bounded()
                assertEquals(1, victim.aborts.get(), "iteration $iteration")
            }
        } finally {
            closePool(pool)
        }
    }

    @Test fun abortRacingWithCompletionNeverAbortsTwiceAndResetStartsACleanCycle() {
        val pool = Executors.newFixedThreadPool(3, NamedThreads())
        try {
            repeat(200) { iteration ->
                val slot = AbortableSlot<Probe> { it.aborts.incrementAndGet() }
                val completed = Probe("synthetic-done-$iteration")
                assertTrue(slot.register(completed))
                val go = CountDownLatch(1)
                val release = submitValue(pool) {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    slot.release(completed)
                    Unit
                }
                val abort = submitValue(pool) {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    slot.abortCurrent()
                    Unit
                }
                go.countDown()
                release.bounded()
                abort.bounded()
                val aborts = completed.aborts.get()
                assertTrue(aborts == 0 || aborts == 1, "iteration $iteration: aborts=$aborts")
                slot.reset()
                val next = Probe("synthetic-next-$iteration")
                assertTrue(slot.register(next), "iteration $iteration: reset failed")
                slot.abortCurrent()
                assertEquals(1, next.aborts.get(), "iteration $iteration")
                assertEquals(aborts, completed.aborts.get(), "iteration $iteration: completed value aborted late")
            }
        } finally {
            closePool(pool)
        }
    }

    @Test fun repeatedBlockedWaitersAndAbortersLeaveNoThreadsAndNeverDeadlock() {
        val pool = Executors.newFixedThreadPool(6, NamedThreads())
        try {
            repeat(50) { cycle ->
                val slot = AbortableSlot<Probe> { it.aborts.incrementAndGet() }
                val probes = List(5) { index -> Probe("synthetic-waiter-$cycle-$index") }
                val go = CountDownLatch(1)
                val finish = CountDownLatch(1)
                val waiters = probes.map { probe ->
                    submitValue(pool) {
                        go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        val accepted = slot.register(probe)
                        finish.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        if (accepted) slot.release(probe)
                        accepted
                    }
                }
                val aborter = submitValue(pool) {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    slot.abortCurrent()
                    Unit
                }
                go.countDown()
                aborter.bounded()
                finish.countDown()
                waiters.forEach { it.bounded() }
                slot.abortCurrent()
                probes.forEach {
                    val aborts = it.aborts.get()
                    assertTrue(aborts == 0 || aborts == 1, "cycle $cycle ${it.label}: aborts=$aborts")
                }
                slot.reset()
                val next = Probe("synthetic-cycle-$cycle")
                assertTrue(slot.register(next), "cycle $cycle: reset failed")
                slot.abortCurrent()
                assertEquals(1, next.aborts.get(), "cycle $cycle")
            }
        } finally {
            closePool(pool)
        }
    }

    @Test fun interruptingAThreadInsideAnAbortCallbackLeavesTheSlotStateConsistent() {
        val entered = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val victimHolder = arrayOfNulls<Probe>(1)
        val slot = AbortableSlot<Probe> { value ->
            if (value === victimHolder[0]) {
                entered.countDown()
                try {
                    blocked.await(WAIT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted.set(true)
                }
            }
            value.aborts.incrementAndGet()
        }
        val victim = Probe("synthetic-blocked")
        victimHolder[0] = victim
        assertTrue(slot.register(victim))
        val aborter = Thread({ slot.abortCurrent() }, "${THREAD_PREFIX}aborter").apply { isDaemon = true }
        try {
            aborter.start()
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "callback never entered")
            aborter.interrupt()
            aborter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse(aborter.isAlive, "aborter did not return after interrupt")
            assertTrue(interrupted.get(), "callback did not observe the interrupt")
            assertEquals(1, victim.aborts.get())
            val late = Probe("synthetic-late")
            assertFalse(slot.register(late), "cancellation state lost after interruption")
            assertEquals(1, late.aborts.get())
        } finally {
            blocked.countDown()
            aborter.interrupt()
            aborter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        }
        slot.reset()
        val next = Probe("synthetic-next")
        assertTrue(slot.register(next), "reset failed after interruption")
        slot.abortCurrent()
        assertEquals(1, next.aborts.get())
    }

    @Test fun slotHasNoDiagnosticChannelThatCanCarrySyntheticMarkerText() {
        // Markers are assembled at runtime; no provider URL, credential, key or payload literal exists in source.
        val markers = listOf(
            charArrayOf('s', 'y', 'n', 't', 'h', '-', 'p', 'i', 'n', '-', '8', '1', '2', '3'),
            charArrayOf('s', 'y', 'n', 't', 'h', '-', 'k', 'e', 'y', '-', '8', '1', '2', '3'),
            charArrayOf('s', 'y', 'n', 't', 'h', '-', 'p', 'a', 'y', 'l', 'o', 'a', 'd', '-', '8', '1', '2', '3')
        ).map { String(it) }
        val slot = AbortableSlot<Probe> { it.aborts.incrementAndGet() }
        markers.forEachIndexed { index, marker ->
            val value = Probe(marker)
            assertTrue(slot.register(value), "index $index")
            slot.abortCurrent()
            assertEquals(1, value.aborts.get(), "index $index")
            slot.reset()
        }
        // The only failure channel would be a thrown exception; all of the calls above completed.

        val type = AbortableSlot::class.java
        assertTrue(
            type.declaredMethods.none { it.returnType == String::class.java },
            "slot exposes a String-returning diagnostic method"
        )
        val instanceFields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(
            instanceFields.none {
                Collection::class.java.isAssignableFrom(it.type) ||
                    Map::class.java.isAssignableFrom(it.type) || it.type.isArray
            },
            "slot retains a collection of marker-bearing values"
        )
        val classBytes = assertNotNull(type.getResourceAsStream("AbortableSlot.class")?.use { it.readBytes() })
        val decoded = classBytes.toString(Charsets.ISO_8859_1)
        markers.forEach { assertFalse(decoded.contains(it), "synthetic marker present in class bytes") }
        assertFalse(decoded.contains("://"), "URL syntax present in class bytes")
        assertFalse(decoded.contains("password"), "credential term present in class bytes")
    }
}

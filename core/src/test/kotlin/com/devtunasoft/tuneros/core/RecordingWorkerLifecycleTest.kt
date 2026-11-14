package com.devtunasoft.tuneros.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingWorkerLifecycleTest {
    @Test fun cleanupWaitsForInFlightRecordingFinalizationWithoutBlockingCaller() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val phase = AtomicInteger(0)
        val error = AtomicReference<Throwable?>(null)
        try {
            worker.execute {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) return@execute
                phase.set(1) // Represents final SQLite write by the recording worker.
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) {
                if (phase.get() != 1) error.set(AssertionError("Cleanup ran before finalization"))
                phase.set(2)
                cleaned.countDown()
            }
            assertTrue(worker.isShutdown)
            assertFalse(cleaned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, phase.get())
            release.countDown()
            assertTrue(cleaned.await(5, TimeUnit.SECONDS))
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertNull(error.get())
            assertEquals(2, phase.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun idleWorkerClosesAndTerminatesWithoutAdditionalThreads() {
        val worker = Executors.newSingleThreadExecutor()
        val cleaned = CountDownLatch(1)
        try {
            RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) { cleaned.countDown() }
            assertTrue(cleaned.await(5, TimeUnit.SECONDS))
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
        }
    }
}

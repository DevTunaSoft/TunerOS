package com.devtunasoft.tuneros.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CORE-18 adversarial coverage for the RecordingWorkerLifecycle seam only.
 *
 * A rejected recording segment must not prevent the terminal persistence cleanup from running.
 * All waits are bounded, each worker is shut down in finally, and no diagnostic text is emitted
 * by this seam (the class carries no URL, credential or filesystem machinery).
 */
class RecordingWorkerLifecycleAdversarialTest {
    @Test fun cleanupStillRunsWhenAPendingSegmentTaskThrows() {
        val worker = Executors.newSingleThreadExecutor()
        val failed = AtomicBoolean(false)
        val order = AtomicInteger(0)
        val cleanupPhase = AtomicInteger(-1)
        val cleaned = CountDownLatch(1)
        try {
            worker.execute {
                failed.set(true)
                order.set(1)
                throw IllegalStateException("synthetic rejected segment")
            }
            RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) {
                cleanupPhase.set(order.get())
                cleaned.countDown()
            }
            assertTrue(cleaned.await(5, TimeUnit.SECONDS))
            assertTrue(failed.get())
            assertEquals(1, cleanupPhase.get())
            assertTrue(worker.isShutdown)
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun shutdownAfterCleanupRejectsFurtherWorkWithoutRerunningCleanup() {
        val worker = Executors.newSingleThreadExecutor()
        val cleanups = AtomicInteger(0)
        val firstCleanup = CountDownLatch(1)
        try {
            RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) {
                cleanups.incrementAndGet()
                firstCleanup.countDown()
            }
            assertTrue(firstCleanup.await(5, TimeUnit.SECONDS))
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertFailsWith<RejectedExecutionException> {
                RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) { cleanups.incrementAndGet() }
            }
            assertEquals(1, cleanups.get())
            assertTrue(worker.isTerminated)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun repeatedBoundedCyclesTerminateWithOneCleanupEach() {
        repeat(32) { cycle ->
            val worker = Executors.newSingleThreadExecutor()
            val cleanups = AtomicInteger(0)
            val work = AtomicInteger(0)
            val done = CountDownLatch(1)
            try {
                worker.execute { work.incrementAndGet() }
                RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) {
                    cleanups.incrementAndGet()
                    done.countDown()
                }
                assertTrue(done.await(5, TimeUnit.SECONDS), "cycle $cycle cleanup")
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "cycle $cycle termination")
                assertEquals(1, work.get(), "cycle $cycle work")
                assertEquals(1, cleanups.get(), "cycle $cycle cleanup count")
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test fun lifecycleSeamCarriesNoUrlCredentialOrPathMachinery() {
        val bytes = RecordingWorkerLifecycle::class.java.getResourceAsStream(
            "/com/devtunasoft/tuneros/core/RecordingWorkerLifecycle.class",
        )!!.use { it.readBytes() }
        val bytecode = String(bytes, Charsets.ISO_8859_1)
        for (forbidden in listOf("java/io/File", "java/nio/file", "java/net/URI", "http", "token", "password")) {
            assertFalse(forbidden in bytecode, "RecordingWorkerLifecycle must not reference $forbidden")
        }
    }
}

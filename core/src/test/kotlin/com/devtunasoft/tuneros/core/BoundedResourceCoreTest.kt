package com.devtunasoft.tuneros.core

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded-resource evidence for the pure-Kotlin core slice. All data is synthetic and
 * deterministic; no provider URLs, credentials or real recording payloads are used.
 */
class BoundedResourceCoreTest {
    private class GeneratedInput(private val total: Long) : InputStream() {
        var position: Long = 0
            private set

        override fun read(): Int = error("bulk read only")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= total) return -1
            val count = minOf(len.toLong(), total - position).toInt()
            for (i in 0 until count) b[off + i] = ((position + i) and 0x7F).toByte()
            position += count
            return count
        }
    }

    private class CountingSink : OutputStream() {
        var bytes: Long = 0
            private set

        override fun write(b: Int) = error("bulk write only")

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes += len
        }
    }

    @Test fun multiviewSessionStaysBoundedAcrossRepeatedOpenCloseCycles() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(4, 4, 4))
        val capacity = session.maxTiles
        repeat(5_000) { cycle ->
            val ids = (0 until capacity).map { "tile-$cycle-$it" }
            ids.forEach { assertEquals(MultiviewSession.Admission.ADMITTED, session.open(it)) }
            assertEquals(MultiviewSession.Admission.AT_CAPACITY, session.open("overflow-$cycle"))
            assertEquals(capacity, session.openTiles.size)
            ids.forEach { assertTrue(session.close(it)) }
            assertTrue(session.openTiles.isEmpty())
            assertNull(session.audibleTile)
        }
        // No retained state: a fresh tile behaves exactly as it would on a new session.
        assertEquals(MultiviewSession.Admission.ADMITTED, session.open("fresh-a"))
        assertEquals(listOf("fresh-a"), session.openTiles)
        assertEquals("fresh-a", session.audibleTile)
    }

    @Test fun boundedCoreClassesHaveNoPerCycleRegistryFields() {
        fun storedCollections(clazz: Class<*>) = clazz.declaredFields
            .filterNot { it.isSynthetic }
            .filter { field ->
                MutableCollection::class.java.isAssignableFrom(field.type) ||
                    Map::class.java.isAssignableFrom(field.type) ||
                    field.type.isArray
            }
            .map { it.name }
            .sorted()
        assertEquals(listOf("tiles"), storedCollections(MultiviewSession::class.java))
        assertEquals(emptyList<String>(), storedCollections(StreamRecorder::class.java))
        assertEquals(emptyList<String>(), storedCollections(RecordingWorkerLifecycle::class.java))
    }

    @Test fun longSyntheticStreamUsesAFixedBufferAndRetainsNoState() {
        val recorder = StreamRecorder(bufferBytes = 4096, minFreeBytes = 0)
        val total = 8L * 1024 * 1024
        val input = GeneratedInput(total)
        val sink = CountingSink()
        val callerBuffer = ByteArray(4096)
        var progressCalls = 0
        var lastProgress = 0L
        val result = recorder.copy(
            input,
            sink,
            freeBytes = { Long.MAX_VALUE },
            onProgress = { value ->
                progressCalls++
                assertTrue(value >= lastProgress)
                assertTrue(value <= total)
                lastProgress = value
            },
            buffer = callerBuffer,
        )
        assertEquals(StreamRecorder.StopReason.END_OF_STREAM, result.stopReason)
        assertEquals(total, result.bytesWritten)
        assertEquals(total, sink.bytes)
        assertEquals(total, input.position)
        assertEquals(4096, callerBuffer.size)
        assertEquals(total, lastProgress)
        assertTrue(progressCalls <= total / 4096)

        // The same instance has no retained byte count or buffer between recordings.
        val second = recorder.copy(
            GeneratedInput(total),
            CountingSink(),
            freeBytes = { Long.MAX_VALUE },
            buffer = ByteArray(4096),
        )
        assertEquals(result, second)
    }

    @Test fun repeatedTerminalShutdownCreatesOneThreadPerWorkerAndReleasesIt() {
        val prefix = "perf02-bounded-"
        repeat(40) { index ->
            val name = "$prefix$index"
            val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
            val cleaned = CountDownLatch(1)
            try {
                worker.execute { }
                RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) { cleaned.countDown() }
                assertTrue(cleaned.await(5, TimeUnit.SECONDS))
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            } finally {
                worker.shutdownNow()
            }
        }
        val leaked = Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name.startsWith(prefix) }
            .map { it.name }
        assertEquals(emptyList<String>(), leaked)
    }

    @Test fun repeatedTerminalShutdownIsIdempotent() {
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "perf02-idempotent") }
        val cleanups = AtomicInteger()
        try {
            RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) { cleanups.incrementAndGet() }
            // onDestroy can follow an earlier terminal callback; it must not reject or resubmit cleanup.
            RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) { cleanups.incrementAndGet() }
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, cleanups.get())
        } finally {
            worker.shutdownNow()
        }
    }
}

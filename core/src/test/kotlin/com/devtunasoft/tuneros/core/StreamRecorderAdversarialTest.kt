package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CORE-18 adversarial coverage for the StreamRecorder seam only.
 *
 * Contract used here: InputStream.read(b, off, len) returns -1 at end of stream or 0..len bytes.
 * Any other count is malformed and must fail closed: no bytes written, no false END_OF_STREAM,
 * no raw count echoed in diagnostics, and caller-owned streams left open. Truncated input is
 * reported as the bytes actually delivered. This seam owns no output filename or path, so path
 * safety is asserted as "no file created and no path/URL machinery in the class", and secret
 * safety as "no provider marker in generated diagnostics".
 */
class StreamRecorderAdversarialTest {
    private class CountingOutput : ByteArrayOutputStream() {
        var closed = false
            private set
        var writeCalls = 0
            private set

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writeCalls++
            super.write(bytes, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun singleReadInput(count: Int): InputStream = object : InputStream() {
        private var delivered = false
        override fun read(): Int = error("bulk read required")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delivered) return -1
            delivered = true
            return count
        }
    }

    @Test fun negativeCountOtherThanEofFailsClosedWithoutClaimingEndOfStream() {
        val output = CountingOutput()
        val error = assertFailsWith<IOException> {
            StreamRecorder(bufferBytes = 4096, minFreeBytes = 0).copy(
                singleReadInput(-2), output, freeBytes = { Long.MAX_VALUE },
            )
        }
        assertEquals(0, output.writeCalls)
        assertEquals(0, output.size())
        assertFalse(output.closed)
        val message = error.message.orEmpty()
        assertTrue(message.isNotBlank() && message.length <= 120)
        assertFalse("-2" in message)
        assertFalse("://" in message)
    }

    @Test fun countLargerThanTheReadBufferIsRejectedBeforeAnyWrite() {
        val output = CountingOutput()
        val error = assertFailsWith<IOException> {
            StreamRecorder(bufferBytes = 4096, minFreeBytes = 0).copy(
                singleReadInput(4097), output, freeBytes = { Long.MAX_VALUE },
            )
        }
        assertEquals(0, output.writeCalls)
        assertEquals(0, output.size())
        assertFalse(output.closed)
        val message = error.message.orEmpty()
        assertTrue(message.isNotBlank() && message.length <= 120)
        assertFalse("4097" in message)
    }

    @Test fun truncatedInputReportsOnlyBytesActuallyDelivered() {
        val input = object : InputStream() {
            private val chunks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
            private var index = 0
            override fun read(): Int = error("bulk read required")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= chunks.size) return -1
                val chunk = chunks[index++]
                chunk.copyInto(b, off)
                return chunk.size
            }
        }
        val output = CountingOutput()
        val progress = ArrayList<Long>()
        val result = StreamRecorder(bufferBytes = 4096, minFreeBytes = 0).copy(
            input, output, freeBytes = { Long.MAX_VALUE }, onProgress = { progress.add(it) },
        )
        assertEquals(StreamRecorder.StopReason.END_OF_STREAM, result.stopReason)
        assertEquals(5L, result.bytesWritten)
        assertEquals(listOf(3L, 5L), progress)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), output.toByteArray().toList())
        assertFalse(output.closed)
    }

    @Test fun repeatedCyclesKeepAccountingPerCallWithoutRetainedState() {
        val recorder = StreamRecorder(bufferBytes = 4096, minFreeBytes = 0)
        val random = Random(20261123)
        var expectedTotal = 0L
        var writtenTotal = 0L
        repeat(256) { cycle ->
            val size = 1 + random.nextInt(4096 * 2 + 17)
            val payload = ByteArray(size) { (it + cycle).toByte() }
            val output = CountingOutput()
            val progress = ArrayList<Long>()
            val result = recorder.copy(
                ByteArrayInputStream(payload), output, freeBytes = { Long.MAX_VALUE },
                onProgress = { progress.add(it) },
            )
            assertEquals(StreamRecorder.StopReason.END_OF_STREAM, result.stopReason, "cycle $cycle")
            assertEquals(size.toLong(), result.bytesWritten, "cycle $cycle")
            assertEquals(size.toLong(), progress.last(), "cycle $cycle")
            assertTrue(progress.indices.all { index ->
                progress[index] > 0 && (index == 0 || progress[index] > progress[index - 1])
            }, "progress must increase monotonically in cycle $cycle")
            assertEquals(payload.toList(), output.toByteArray().toList(), "cycle $cycle")
            assertFalse(output.closed)
            expectedTotal += size.toLong()
            writtenTotal += result.bytesWritten
        }
        assertEquals(expectedTotal, writtenTotal)
        val fields = StreamRecorder::class.java.declaredFields.filter { field ->
            !field.isSynthetic && !java.lang.reflect.Modifier.isStatic(field.modifiers)
        }
        assertEquals(setOf("bufferBytes", "minFreeBytes"), fields.map { it.name }.toSet())
        assertTrue(fields.all { field ->
            field.type == Int::class.javaPrimitiveType || field.type == Long::class.javaPrimitiveType
        })
    }

    @Test fun rejectedSegmentLeavesNoFilesAndCallerStreamsUsableForNextCycle() {
        val scratch = Files.createTempDirectory("core18-recorder-")
        try {
            val before = Files.list(scratch).use { it.count() }
            val recorder = StreamRecorder(bufferBytes = 4096, minFreeBytes = 0)
            val output = CountingOutput()
            val accepted = byteArrayOf(7, 8, 9)
            val input = object : InputStream() {
                private var phase = 0
                override fun read(): Int = error("bulk read required")
                override fun read(b: ByteArray, off: Int, len: Int): Int = when (phase++) {
                    0 -> { accepted.copyInto(b, off); accepted.size }
                    else -> -3 // malformed rejected segment
                }
            }
            assertFailsWith<IOException> {
                recorder.copy(input, output, freeBytes = { Long.MAX_VALUE })
            }
            assertEquals(accepted.toList(), output.toByteArray().toList())
            assertFalse(output.closed)
            assertEquals(before, Files.list(scratch).use { it.count() })

            val next = byteArrayOf(11, 12)
            val result = recorder.copy(ByteArrayInputStream(next), output, freeBytes = { Long.MAX_VALUE })
            assertEquals(StreamRecorder.StopReason.END_OF_STREAM, result.stopReason)
            assertEquals(2L, result.bytesWritten)
            assertEquals(listOf<Byte>(7, 8, 9, 11, 12), output.toByteArray().toList())
            assertFalse(output.closed)
        } finally {
            Files.list(scratch).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(scratch)
        }
    }

    @Test fun generatedDiagnosticsCarryNoProviderMarkersOrPathSeparators() {
        val seedHex = Integer.toHexString(0xC018A25)
        val urlMarker = "https://provider.invalid/live/seg-$seedHex.ts?token=$seedHex"
        val credentialMarker = "viewer:$seedHex"
        val markers = listOf(urlMarker, credentialMarker, "://", "/", "\\", "@")
        val messages = ArrayList<String>()
        messages += assertFailsWith<IllegalArgumentException> {
            StreamRecorder(bufferBytes = 4096, minFreeBytes = 0).copy(
                ByteArrayInputStream(byteArrayOf(1)), CountingOutput(),
                freeBytes = { Long.MAX_VALUE }, buffer = ByteArray(100),
            )
        }.message.orEmpty()
        messages += assertFailsWith<IllegalArgumentException> {
            StreamRecorder(minFreeBytes = 10).copy(
                ByteArrayInputStream(byteArrayOf(1)), CountingOutput(), freeBytes = { 0 },
            )
        }.message.orEmpty()
        val hostile = object : InputStream() {
            override fun toString(): String = urlMarker + credentialMarker
            override fun read(): Int = error("bulk read required")
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        messages += assertFailsWith<IOException> {
            StreamRecorder(bufferBytes = 4096, minFreeBytes = 0).copy(
                hostile, CountingOutput(), freeBytes = { Long.MAX_VALUE },
            )
        }.message.orEmpty()
        for (message in messages) {
            assertTrue(message.isNotBlank() && message.length <= 120)
            markers.forEach { marker -> assertFalse(marker in message, "diagnostic leaked $marker") }
        }
        val bytes = StreamRecorder::class.java.getResourceAsStream(
            "/com/devtunasoft/tuneros/core/StreamRecorder.class",
        )!!.use { it.readBytes() }
        val bytecode = String(bytes, Charsets.ISO_8859_1)
        for (forbidden in listOf("java/io/File", "java/nio/file", "java/net/URI", "http", "token", "password")) {
            assertFalse(forbidden in bytecode, "StreamRecorder must not reference $forbidden")
        }
    }
}

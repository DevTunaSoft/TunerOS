package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamRecorderTest {
    private val recorder = StreamRecorder(bufferBytes = 4096, minFreeBytes = 0)

    @Test fun permanentlyEmptyInputFailsInsteadOfSpinning() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("single-byte read should not be used")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return 0
            }
        }
        assertFailsWith<IOException> {
            recorder.copy(input, ByteArrayOutputStream(), freeBytes = { Long.MAX_VALUE })
        }
        assertEquals(16, reads)
    }

    @Test fun successfulReadResetsNoProgressBudget() {
        var empty = 0
        var position = 0
        val bytes = byteArrayOf(1, 2, 3)
        val input = object : InputStream() {
            override fun read(): Int = error("single-byte read should not be used")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position == bytes.size) return -1
                if (empty++ % 8 != 7) return 0
                b[off] = bytes[position++]
                return 1
            }
        }
        val out = ByteArrayOutputStream()
        val result = recorder.copy(input, out, freeBytes = { Long.MAX_VALUE })
        assertEquals(StreamRecorder.StopReason.END_OF_STREAM, result.stopReason)
        assertEquals(3L, result.bytesWritten)
        assertEquals(bytes.toList(), out.toByteArray().toList())
    }

    @Test fun cancellationStopsAStalledInputBeforeBudgetExhaustion() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("single-byte read should not be used")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return 0
            }
        }
        val result = recorder.copy(
            input, ByteArrayOutputStream(),
            freeBytes = { Long.MAX_VALUE },
            cancelled = { reads >= 3 },
        )
        assertEquals(StreamRecorder.StopReason.CANCELLED, result.stopReason)
        assertEquals(3, reads)
    }
}

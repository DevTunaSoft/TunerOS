package com.devtunasoft.tuneros.core

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.lang.reflect.Modifier
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage of the shared single-line helper.
 *
 * BoundedLines owns one raw line per call: its cap counts characters decoded by the caller's
 * Reader, and no input beyond the first rejected character is consumed. The per-parse line-count
 * cap belongs to the parser callers, so one caller cap is exercised here as the observable seam;
 * parser fuzz suites cover the remaining parser rules. UTF-8 decoding also belongs to the caller:
 * both a strict decoder (fail closed) and the replacement decoder are exercised at this seam.
 */
class BoundedLinesAdversarialTest {
    /** Counts read() calls at the helper's seam; BufferedReader's own fill is not counted. */
    private class CountingBufferedReader(text: String) : BufferedReader(StringReader(text)) {
        var reads = 0
            private set

        override fun read(): Int = super.read().also { reads++ }
    }

    // Derived at run time from a fixed seed: no literal credential or provider text in the repo.
    private val marker = "MK-" + (0x5A17C3L xor 0x1234L).toString(16).uppercase()

    @Test fun acceptsLineExactlyAtCapAndStopsAtFirstCharacterPastCap() {
        val atCap = CountingBufferedReader("abcdef\n")
        assertEquals("abcdef", BoundedLines.read(atCap, 6, "test"))
        assertEquals(7, atCap.reads) // six characters plus the line feed

        val overCap = CountingBufferedReader("abcdefg\n")
        val refusal = assertFailsWith<IllegalArgumentException> {
            BoundedLines.read(overCap, 6, "test")
        }
        assertEquals(7, overCap.reads) // cap + 1: nothing after the rejected character is read
        assertTrue(refusal.message.orEmpty().contains("6"))
        assertFalse(refusal.message.orEmpty().contains("abcdefg"))
    }

    @Test fun millionCharacterLineIsRejectedAfterCapPlusOneReadWithNoRetainedState() {
        val reader = CountingBufferedReader("A".repeat(1_000_000) + "\n")
        assertFailsWith<IllegalArgumentException> { BoundedLines.read(reader, 8, "test") }
        assertEquals(9, reader.reads)
        assertTrue(BoundedLines::class.java.declaredFields.all {
            Modifier.isStatic(it.modifiers) || it.isSynthetic
        })
    }

    @Test fun terminatorsAndFinalLineWithoutNewlineAreHandledDeterministically() {
        assertEquals("alpha", BoundedLines.read(BufferedReader(StringReader("alpha\r\n")), 16, "test"))
        assertEquals("alpha", BoundedLines.read(BufferedReader(StringReader("alpha\n")), 16, "test"))
        assertEquals("alpha", BoundedLines.read(BufferedReader(StringReader("alpha")), 16, "test"))
        assertEquals("alpha", BoundedLines.read(BufferedReader(StringReader("alpha\r")), 16, "test"))
        assertEquals("", BoundedLines.read(BufferedReader(StringReader("\n")), 16, "test"))

        val mixed = BufferedReader(StringReader("one\r\ntwo\nthree"))
        assertEquals("one", BoundedLines.read(mixed, 16, "test"))
        assertEquals("two", BoundedLines.read(mixed, 16, "test"))
        assertEquals("three", BoundedLines.read(mixed, 16, "test"))
        assertEquals(null, BoundedLines.read(mixed, 16, "test"))
    }

    @Test fun emptyInputReturnsNullAndNonPositiveCapIsRejectedBeforeReading() {
        assertEquals(null, BoundedLines.read(BufferedReader(StringReader("")), 1, "test"))
        val reader = CountingBufferedReader("x")
        assertFailsWith<IllegalArgumentException> { BoundedLines.read(reader, 0, "test") }
        assertEquals(0, reader.reads)
    }

    @Test fun strictDecoderFailsClosedOnMalformedUtf8() {
        val bytes = byteArrayOf(0x41, 0xFF.toByte(), 0x42, 0x0A)
        val strict = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        assertFailsWith<MalformedInputException> {
            BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), strict)).use {
                BoundedLines.read(it, 64, "test")
            }
        }
    }

    @Test fun replacementDecoderStaysWithinCapAndNeverEmitsRawBytes() {
        val bytes = byteArrayOf(0x41, 0xFF.toByte(), 0x42, 0x0A)
        val replacement = "A" + Char(0xFFFD) + "B"
        BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).use {
            assertEquals(replacement, BoundedLines.read(it, 3, "test"))
        }
        assertFailsWith<IllegalArgumentException> {
            BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).use {
                BoundedLines.read(it, 2, "test")
            }
        }
    }

    @Test fun refusalDiagnosticsNeverEchoMarkerBearingLineOrCallerLabel() {
        val line = marker.repeat(8)
        val refusal = assertFailsWith<IllegalArgumentException> {
            BoundedLines.read(BufferedReader(StringReader(line)), marker.length * 4, "test")
        }
        val message = refusal.message.orEmpty()
        assertFalse(message.contains(marker), "line material leaked: $message")
        assertFalse(message.contains(line.take(8)), "line prefix leaked: $message")

        val badCap = assertFailsWith<IllegalArgumentException> {
            BoundedLines.read(BufferedReader(StringReader("x")), 0, marker)
        }
        assertFalse(badCap.message.orEmpty().contains(marker))
    }

    @Test fun callerLineCountCapStopsPlaylistBeforeUnboundedLineHistory() {
        // PlaylistParser.MAX_LINES is private; 200_000 is the cap pinned in its source.
        val text = buildString { repeat(200_001) { append("#x\n") } }
        val refusal = assertFailsWith<IllegalArgumentException> {
            PlaylistParser.parse(StringReader(text))
        }
        assertTrue(refusal.message.orEmpty().contains("200000"), refusal.message)
        assertFalse(refusal.message.orEmpty().contains(marker))
    }
}

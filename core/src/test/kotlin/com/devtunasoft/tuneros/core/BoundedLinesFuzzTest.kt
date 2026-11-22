package com.devtunasoft.tuneros.core

import java.io.BufferedReader
import java.io.StringReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic fuzzing of the bounded line reader that every text import uses. The reader refuses a text exactly when
 * one raw line, counting any carriage return before its line feed, is longer than the limit. An accepted line is never
 * longer than the limit and never contains a line feed. Every iteration is seeded, so a failure reproduces from the
 * iteration number.
 */
class BoundedLinesFuzzTest {
    private val alphabet = listOf('a', 'b', '\r', '\n', 'é', 'x', ' ')

    /** Reads every line, or returns null when the reader refuses an over-long line. */
    private fun readAll(text: String, maxLength: Int): List<String>? {
        val reader = BufferedReader(StringReader(text))
        val lines = ArrayList<String>()
        return try {
            while (true) {
                lines += BoundedLines.read(reader, maxLength, "fuzz") ?: break
            }
            lines
        } catch (refused: IllegalArgumentException) {
            null
        }
    }

    @Test fun refusesExactlyTheTextsWithAnOverLongRawLineAndNeverReturnsALongerLine() {
        val random = Random(20261122)
        var refusals = 0
        var accepted = 0
        repeat(3_000) { iteration ->
            val text = buildString { repeat(random.nextInt(0, 120)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val maxLength = random.nextInt(1, 40)
            val expectedRefusal = text.split('\n').any { it.length > maxLength }
            val lines = readAll(text, maxLength)
            assertEquals(expectedRefusal, lines == null, "iteration $iteration")
            if (lines == null) {
                refusals++
            } else {
                accepted++
                assertTrue(lines.all { it.length <= maxLength && !it.contains('\n') }, "iteration $iteration")
            }
        }
        // Both outcomes must be common, or the oracle above could pass without checking one of them.
        assertTrue(refusals >= 100 && accepted >= 100, "refusals $refusals, accepted $accepted")
    }
}

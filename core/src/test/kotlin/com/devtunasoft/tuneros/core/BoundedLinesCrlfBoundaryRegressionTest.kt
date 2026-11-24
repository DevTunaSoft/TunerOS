package com.devtunasoft.tuneros.core

import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CORE-60: a carriage return is a line terminator, not line content. BoundedLines already
 * strips a trailing CR from the text it returns, so a CRLF line whose content is exactly the
 * configured cap must be accepted just like its LF counterpart. All fixtures are synthetic.
 */
class BoundedLinesCrlfBoundaryRegressionTest {
    private fun read(text: String, cap: Int): String? =
        BoundedLines.read(BufferedReader(StringReader(text)), cap, "scratch")

    @Test fun aCrLfLineAtTheExactCapIsAcceptedLikeItsLfCounterpart() {
        val content = "scratch-".repeat(2) // 16 characters
        assertEquals(content, read(content + "\n", 16))
        assertEquals(content, read(content + "\r\n", 16))
    }

    @Test fun aFinalCarriageReturnAtTheExactCapIsAccepted() {
        val content = "0123456789abcdef" // 16 characters
        assertEquals(content, read(content + "\r", 16))
    }

    @Test fun aCarriageReturnAtTheCapFollowedByMoreContentIsStillRefused() {
        val content = "0123456789abcdef"
        val failure = assertFailsWith<IllegalArgumentException> {
            read(content + "\r tail\n", 16)
        }
        assertEquals(true, failure.message.orEmpty().contains("16"))
        assertEquals(false, failure.message.orEmpty().contains("tail"))
    }

    @Test fun aShorterCrLfLineIsUnchangedAcrossTheCapBoundary() {
        val content = "0123456789abcde" // 15 characters
        assertEquals(content, read(content + "\r\n", 16))
        assertEquals(content, read(content + "\n", 16))
    }
}

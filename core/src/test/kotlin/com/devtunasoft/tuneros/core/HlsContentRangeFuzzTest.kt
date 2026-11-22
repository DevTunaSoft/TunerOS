package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic fuzzing of the HLS Content-Range check. Random headers and ranges must never throw. When the check
 * accepts a header, the header's start and end must be exactly the requested bytes. Headers built from the requested
 * range are mixed in, so enough of them match to check the accepting path. Every iteration is seeded, so a failure
 * reproduces from the iteration number.
 */
class HlsContentRangeFuzzTest {
    private val pattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
    private val pieces = listOf(
        "bytes", "bytes ", " ", "-", "/", "*", "0", "1", "9", "99999999999999999999999", "x", "", "é", "\t",
    )

    @Test fun randomHeadersNeverThrowAndAnAcceptedHeaderNamesTheRequestedBytes() {
        val random = Random(20261122)
        var accepted = 0
        repeat(3_000) { iteration ->
            val requested = HlsByteRange(length = random.nextLong(-2L, 1_000L), offset = random.nextLong(-2L, 1_000L))
            val header = if (random.nextInt(3) == 0) {
                // A header built from the requested range, sometimes with a total that is too small or missing.
                val end = requested.offset + requested.length - 1
                val total = if (random.nextInt(4) == 0) "*" else (end + random.nextLong(-1L, 50L)).toString()
                "bytes ${requested.offset}-$end/$total"
            } else {
                buildString { repeat(random.nextInt(0, 8)) { append(pieces[random.nextInt(pieces.size)]) } }
            }
            if (HlsContentRange.matches(header, requested)) {
                accepted++
                val groups = pattern.matchEntire(header.trim())!!.groupValues
                assertEquals(requested.offset, groups[1].toLong(), "iteration $iteration")
                assertEquals(requested.offset + requested.length - 1, groups[2].toLong(), "iteration $iteration")
            }
        }
        // Refusing everything would pass vacuously, so enough headers must be accepted to check the accepting path.
        assertTrue(accepted >= 100, "only $accepted of 3000 headers were accepted")
    }

    @Test fun aMissingHeaderOrAnEmptyRangeIsAlwaysRefused() {
        assertFalse(HlsContentRange.matches(null, HlsByteRange(length = 10L, offset = 0L)))
        assertFalse(HlsContentRange.matches("bytes 0-9/10", HlsByteRange(length = 0L, offset = 0L)))
        assertFalse(HlsContentRange.matches("bytes 0-9/10", HlsByteRange(length = 10L, offset = -1L)))
    }
}

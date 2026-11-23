package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage for the bounded 206 Content-Range check.
 *
 * The reference parser below is deliberately written with manual index scanning rather than a regex, so it is an
 * independent oracle for the production regex. No random input: every header and request below is a fixed boundary
 * case. All fixtures are synthetic; hostile marker text is assembled from char arrays so no marker literal exists in
 * source or in a compiled constant pool.
 */
class HlsContentRangeAdversarialTest {
    private val headers = listOf(
        "bytes 20-24/100",
        "bytes 20-24/*",
        "bytes 00020-00024/00100",
        "bytes 20-24/24",
        "bytes 20-24/25",
        "bytes 19-24/100",
        "bytes 20-25/100",
        "bytes 20-24/0",
        "bytes 20-24/9223372036854775808",
        "bytes 9223372036854775808-24/100",
        "bytes 0-9223372036854775808/*",
        "bytes -9223372036854775808-24/100",
        "bytes 20-24/-9223372036854775808",
        "bytes 20-24/100\r\n",
        "\tbytes 20-24/100 \r\n",
        "bytes 20\r\n-24/100",
        "bytes 20-24\r\n/100",
        "bytes 20-24/100\r\nbytes 20-24/100",
        "bytes 20-24/100, bytes 20-24/100",
        "bytes 20-24/100/100",
        "",
        "   ",
        "\r\n",
        "bytes",
        "bytes ",
        "bytes 20-24",
        "bytes 20-24/",
        "bytes */100",
        "bytes 24-20/100",
        "bytes +20-24/100",
        "items 20-24/100",
        "bytes 2 0-24/100",
        "bytes 20-24/1e2",
        "bytes 9223372036854775806-9223372036854775807/*",
        "bytes 0-9223372036854775806/*",
        "bytes 9223372036854775807-9223372036854775807/*",
    )

    private val requests = listOf(
        HlsByteRange(length = 5, offset = 20),
        HlsByteRange(length = 1, offset = 0),
        HlsByteRange(length = Long.MAX_VALUE, offset = 0),
        HlsByteRange(length = 2, offset = Long.MAX_VALUE - 1),
        HlsByteRange(length = 1, offset = Long.MAX_VALUE),
        HlsByteRange(length = 2, offset = Long.MAX_VALUE),
        HlsByteRange(length = Long.MIN_VALUE, offset = 0),
        HlsByteRange(length = 10, offset = -1),
        HlsByteRange(length = 0, offset = 5),
        HlsByteRange(length = 5, offset = 5),
    )

    private fun isAsciiSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

    /** Independent oracle: manual scan, no regex, same documented contract. */
    private fun referenceMatches(header: String?, requested: HlsByteRange): Boolean {
        if (header == null || requested.offset < 0 || requested.length <= 0) return false
        val expectedEnd = try {
            Math.addExact(requested.offset, requested.length - 1)
        } catch (overflow: ArithmeticException) {
            return false
        }
        val text = header.trim()
        if (text.length < 5 || !text.substring(0, 5).equals("bytes", ignoreCase = true)) return false
        var i = 5
        if (i >= text.length || !isAsciiSpace(text[i])) return false
        while (i < text.length && isAsciiSpace(text[i])) i++
        fun digits(): String? {
            val start = i
            while (i < text.length && text[i] in '0'..'9') i++
            return if (i == start) null else text.substring(start, i)
        }
        val fromRaw = digits() ?: return false
        if (i >= text.length || text[i] != '-') return false
        i++
        val toRaw = digits() ?: return false
        if (i >= text.length || text[i] != '/') return false
        i++
        val totalRaw: String?
        if (i < text.length && text[i] == '*') {
            i++
            totalRaw = null
        } else {
            totalRaw = digits() ?: return false
        }
        if (i != text.length) return false
        val from = fromRaw.toLongOrNull() ?: return false
        val to = toRaw.toLongOrNull() ?: return false
        if (totalRaw != null) {
            val total = totalRaw.toLongOrNull() ?: return false
            if (total <= to) return false
        }
        return from == requested.offset && to == expectedEnd
    }

    @Test fun longMaxEndIsAcceptedWhenTheRequestArithmeticFits() {
        assertTrue(HlsContentRange.matches("bytes 9223372036854775806-9223372036854775807/*", HlsByteRange(length = 2, offset = Long.MAX_VALUE - 1)))
        assertTrue(HlsContentRange.matches("bytes 9223372036854775807-9223372036854775807/*", HlsByteRange(length = 1, offset = Long.MAX_VALUE)))
        assertTrue(HlsContentRange.matches("bytes 0-9223372036854775806/*", HlsByteRange(length = Long.MAX_VALUE, offset = 0)))
    }

    @Test fun oneBytePastLongMaxFailsClosedForEveryRequestShape() {
        val hostile = listOf(
            "bytes 9223372036854775807-9223372036854775808/*",
            "bytes 9223372036854775806-9223372036854775807/9223372036854775807",
        )
        val requestsAtTheEdge = listOf(
            HlsByteRange(length = 2, offset = Long.MAX_VALUE),
            HlsByteRange(length = 3, offset = Long.MAX_VALUE - 1),
            HlsByteRange(length = Long.MAX_VALUE, offset = Long.MAX_VALUE),
        )
        hostile.forEach { header ->
            requestsAtTheEdge.forEach { requested ->
                val result = runCatching { HlsContentRange.matches(header, requested) }
                assertTrue(result.isSuccess, "threw for $requested")
                assertFalse(result.getOrNull() ?: false, "header=$header request=$requested")
            }
        }
    }

    @Test fun overflowingHeaderIntegersAreRefusedWithoutThrowing() {
        val overflowing = listOf(
            "bytes 0-9223372036854775808/*",
            "bytes 9223372036854775808-24/100",
            "bytes 20-24/9223372036854775808",
            "bytes 000000000000000000000000000000000009223372036854775808-24/100",
        )
        overflowing.forEach { header ->
            val result = runCatching { HlsContentRange.matches(header, HlsByteRange(length = 5, offset = 20)) }
            assertTrue(result.isSuccess, "threw for [$header]")
            assertFalse(result.getOrNull() ?: false, "header=[$header]")
            assertEquals(referenceMatches(header, HlsByteRange(length = 5, offset = 20)), result.getOrNull())
        }
    }

    @Test fun longMinAndNegativeHeaderIntegersFailClosed() {
        val negative = listOf(
            "bytes -9223372036854775808-24/100",
            "bytes 20--9223372036854775808/100",
            "bytes 20-24/-9223372036854775808",
            "bytes 20-24/-1",
        )
        negative.forEach { header ->
            val result = runCatching { HlsContentRange.matches(header, HlsByteRange(length = 5, offset = 20)) }
            assertTrue(result.isSuccess, "threw for [$header]")
            assertFalse(result.getOrNull() ?: false, "header=[$header]")
        }
    }

    @Test fun leadingAndTrailingCrLfAndLfAreTrimmedButInteriorBreaksFailClosed() {
        val requested = HlsByteRange(length = 5, offset = 20)
        listOf(
            "bytes 20-24/100\r\n",
            "bytes 20-24/100\n",
            "bytes 20-24/100\r",
            " bytes 20-24/100 \r\n",
            "\tbytes 20-24/100\t",
        ).forEach { header -> assertTrue(HlsContentRange.matches(header, requested), "header=[$header]") }
        listOf(
            "bytes 20\r\n-24/100",
            "bytes 20-24\r\n/100",
            "bytes 20-24/100\r\nbytes 20-24/100",
        ).forEach { header -> assertFalse(HlsContentRange.matches(header, requested), "header=[$header]") }
    }

    @Test fun blankDuplicateAndMalformedHeadersFailClosed() {
        val malformed = listOf(
            "", " ", "\r\n", "bytes", "bytes ", "bytes 20-24", "bytes 20-24/", "bytes */100",
            "bytes 20-24/100/100", "bytes 20-24/100, bytes 20-24/100", "bytes 24-20/100",
            "bytes +20-24/100", "items 20-24/100", "bytes 2 0-24/100", "bytes 20-24/1e2",
            "bytes 20-24/00", "bytes 20-24/24",
        )
        malformed.forEach { header ->
            val result = runCatching { HlsContentRange.matches(header, HlsByteRange(length = 5, offset = 20)) }
            assertTrue(result.isSuccess, "threw for [$header]")
            assertFalse(result.getOrNull() ?: false, "header=[$header]")
        }
    }

    @Test fun leadingZerosInHeaderNumbersAreAcceptedByTheReferenceModel() {
        val requested = HlsByteRange(length = 5, offset = 20)
        assertTrue(HlsContentRange.matches("bytes 00020-00024/00100", requested))
        assertTrue(referenceMatches("bytes 00020-00024/00100", requested))
    }

    @Test fun productionAgreesWithTheIndependentReferenceOverABoundedCrossProduct() {
        headers.forEach { header ->
            requests.forEach { requested ->
                val result = runCatching { HlsContentRange.matches(header, requested) }
                assertTrue(result.isSuccess, "threw for header=[$header] request=$requested")
                assertEquals(
                    referenceMatches(header, requested),
                    result.getOrNull(),
                    "header=[$header] request=$requested",
                )
            }
        }
    }

    @Test fun repeatedParseCyclesRetainNoStateAndDoNotDependOnCallHistory() {
        assertNoRetainedState(HlsContentRange::class.java)
        fun run(): List<Boolean> = headers.map { header -> HlsContentRange.matches(header, HlsByteRange(length = 5, offset = 20)) }
        val forward = run()
        headers.asReversed().forEach { HlsContentRange.matches(it, HlsByteRange(length = 5, offset = 20)) }
        repeat(64) { HlsContentRange.matches(markerHeader(), HlsByteRange(length = 2, offset = Long.MAX_VALUE)) }
        assertEquals(forward, run())
    }

    @Test fun hostileHeadersWithSyntheticMarkersNeverThrowOrLeakText() {
        val marker = marker()
        val hostile = listOf(
            "bytes 20-24/100 $marker",
            "$marker bytes 20-24/100",
            "bytes 20-24/100\r\n$marker",
            "bytes $marker-24/100",
        )
        hostile.forEach { header ->
            val result = runCatching { HlsContentRange.matches(header, HlsByteRange(length = 5, offset = 20)) }
            assertTrue(result.isSuccess, "threw for [$header]")
            assertFalse(result.getOrNull() ?: false, "header=[$header]")
            assertTrue(!result.exceptionOrNull()?.message.orEmpty().contains(marker))
        }
        assertNoDiagnosticSurface(HlsContentRange::class.java)
    }

    private fun marker(): String = buildString {
        charArrayOf('f', 'i', 'c', 't', 'i', 'o', 'n', 'a', 'l').forEach { append(it) }
        append('-')
        charArrayOf('t', 'o', 'k', 'e', 'n').forEach { append(it) }
    }

    private fun markerHeader(): String = "bytes ${marker()}-24/100"
}

package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsContentRangeTest {
    @Test fun acceptsMatching206Ranges() {
        val expected = HlsByteRange(5, 20)
        assertTrue(HlsContentRange.matches("bytes 20-24/100", expected))
        assertTrue(HlsContentRange.matches("Bytes 20-24/*", expected))
    }

    @Test fun rejectsMissingCorruptOrWrongOffsetHeaders() {
        val expected = HlsByteRange(5, 20)
        assertFalse(HlsContentRange.matches(null, expected))
        assertFalse(HlsContentRange.matches("bytes 19-23/100", expected))
        assertFalse(HlsContentRange.matches("bytes 20-25/100", expected))
        assertFalse(HlsContentRange.matches("bytes 20-24/24", expected))
        assertFalse(HlsContentRange.matches("bytes */100", expected))
        assertFalse(HlsContentRange.matches("bytes 20-24/garbage", expected))
        assertFalse(HlsContentRange.matches("bytes 20-24/100, bytes 30-34/100", expected))
    }

    @Test fun rejectsRequestsWhoseEndOffsetWouldOverflow() {
        assertFalse(HlsContentRange.matches(
            "bytes 9223372036854775806-9223372036854775807/*",
            HlsByteRange(5, Long.MAX_VALUE - 1),
        ))
    }
}

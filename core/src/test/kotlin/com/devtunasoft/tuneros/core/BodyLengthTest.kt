package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BodyLengthTest {
    @Test fun identityEncodingKeepsTheDeclaredLengthIgnoringCase() {
        assertEquals(100L, BodyLength.expectedLength(100L, null))
        assertEquals(100L, BodyLength.expectedLength(100L, "identity"))
        assertEquals(100L, BodyLength.expectedLength(100L, "IDENTITY"))
    }

    @Test fun compressedEncodingsHaveNoIdentityLength() {
        assertEquals(-1L, BodyLength.expectedLength(100L, "gzip"))
        assertEquals(-1L, BodyLength.expectedLength(100L, "br"))
    }

    @Test fun anUnknownDeclaredLengthStaysUnknown() {
        assertEquals(-1L, BodyLength.expectedLength(-1L, null))
    }

    @Test fun anUnknownLengthIsNeverRejected() {
        assertEquals(BodyLength.Verdict.UNVERIFIED, BodyLength.verifyCleanEnd(-1L, 0L))
        assertEquals(BodyLength.Verdict.UNVERIFIED, BodyLength.verifyCleanEnd(-1L, 5_000L))
    }

    @Test fun anExactLengthIsCompleteIncludingAnEmptyBody() {
        assertEquals(BodyLength.Verdict.COMPLETE, BodyLength.verifyCleanEnd(0L, 0L))
        assertEquals(BodyLength.Verdict.COMPLETE, BodyLength.verifyCleanEnd(10L, 10L))
    }

    @Test fun aCleanEndBeforeTheDeclaredLengthIsTruncated() {
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(10L, 9L))
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(10L, 0L))
    }

    @Test fun aBodyLongerThanDeclaredIsRejected() {
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(10L, 11L))
    }
}

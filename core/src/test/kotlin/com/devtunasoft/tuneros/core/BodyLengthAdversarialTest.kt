package com.devtunasoft.tuneros.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial coverage of the core body-length verdict rules.
 *
 * This helper does not parse raw HTTP headers: the adapter supplies one resolved long value,
 * with -1 for a missing header. Duplicate-header resolution and header whitespace trimming
 * therefore happen upstream, not in core. The fail-closed equivalent tested here is that no
 * unknown, negative, malformed or non-identity token can ever produce a COMPLETE verdict, and
 * that arithmetic edges are decided without arithmetic wraparound. Pure functions hold no state,
 * so no input string or byte count is retained or echoed.
 */
class BodyLengthAdversarialTest {
    // Derived at run time from a fixed seed; never a literal credential or provider token.
    private val syntheticToken = "TK-" + (0xC0FFEEL - 0x55L).toString(16).uppercase()

    @Test fun longMaximumDeclaredLengthIsPreservedOnlyForIdentityBodies() {
        assertEquals(Long.MAX_VALUE, BodyLength.expectedLength(Long.MAX_VALUE, null))
        assertEquals(Long.MAX_VALUE, BodyLength.expectedLength(Long.MAX_VALUE, "identity"))
        assertEquals(-1L, BodyLength.expectedLength(Long.MAX_VALUE, "gzip; $syntheticToken"))
        assertEquals(
            BodyLength.Verdict.UNVERIFIED,
            BodyLength.verifyCleanEnd(BodyLength.expectedLength(Long.MAX_VALUE, "gzip"), Long.MAX_VALUE),
        )
    }

    @Test fun overflowAndNegativeCountsNeverReportComplete() {
        assertEquals(
            BodyLength.Verdict.TRUNCATED_OR_OVERRUN,
            BodyLength.verifyCleanEnd(Long.MAX_VALUE, Long.MAX_VALUE - 1),
        )
        assertEquals(BodyLength.Verdict.COMPLETE, BodyLength.verifyCleanEnd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(
            BodyLength.Verdict.TRUNCATED_OR_OVERRUN,
            BodyLength.verifyCleanEnd(10L, Long.MAX_VALUE),
        )
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(10L, -1L))
        assertEquals(BodyLength.Verdict.UNVERIFIED, BodyLength.verifyCleanEnd(Long.MIN_VALUE, 0L))
        assertEquals(BodyLength.Verdict.UNVERIFIED, BodyLength.verifyCleanEnd(-2L, Long.MAX_VALUE))
    }

    @Test fun missingHeaderStaysUnverifiedHoweverManyBytesWereCopied() {
        assertEquals(-1L, BodyLength.expectedLength(-1L, null))
        assertEquals(
            BodyLength.Verdict.UNVERIFIED,
            BodyLength.verifyCleanEnd(BodyLength.expectedLength(-1L, null), Long.MAX_VALUE),
        )
        assertEquals(
            BodyLength.Verdict.COMPLETE,
            BodyLength.verifyCleanEnd(BodyLength.expectedLength(42L, null), 42L),
        )
        // A duplicate header that the HTTP layer resolved to one value is just one resolved long here.
        assertEquals(
            BodyLength.Verdict.TRUNCATED_OR_OVERRUN,
            BodyLength.verifyCleanEnd(BodyLength.expectedLength(42L, null), 41L),
        )
    }

    @Test fun mixedCaseIdentityIsKeptButWhitespaceAndParameterisedTokensFailClosed() {
        assertEquals(100L, BodyLength.expectedLength(100L, "IdEnTiTy"))
        for (token in listOf(" identity", "identity ", "identity; q=1", "", "gzip", syntheticToken)) {
            val expected = BodyLength.expectedLength(100L, token)
            assertEquals(-1L, expected, "token must not yield an identity length")
            assertEquals(
                BodyLength.Verdict.UNVERIFIED,
                BodyLength.verifyCleanEnd(expected, 100L),
                "token must not be verified as COMPLETE",
            )
        }
    }

    @Test fun lengthRulesArePureAndHoldNoUnboundedState() {
        assertTrue(BodyLength::class.java.declaredFields.all {
            Modifier.isStatic(it.modifiers) || it.isSynthetic
        })
        repeat(1_000) { i ->
            val declared = if (i % 2 == 0) Long.MAX_VALUE else -1L
            assertEquals(
                BodyLength.Verdict.UNVERIFIED,
                BodyLength.verifyCleanEnd(BodyLength.expectedLength(declared, "gzip"), i.toLong()),
            )
            assertEquals(declared, BodyLength.expectedLength(declared, null))
        }
    }
}

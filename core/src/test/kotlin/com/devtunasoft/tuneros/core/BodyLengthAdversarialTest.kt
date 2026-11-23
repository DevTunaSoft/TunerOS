package com.devtunasoft.tuneros.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage for the bounded HTTP identity-length seam.
 *
 * Scope note: BodyLength owns no I/O, no clock and no diagnostics. It returns a Long or an enum verdict, so the
 * "no provider URL, credential, secret or DRM key in a diagnostic" requirement is expressed here as: no thrown
 * exception (whose message could echo hostile text), no String-returning diagnostic method, no retained collection
 * or array field, and no secret marker literal in the compiled class bytes. All fixtures are synthetic.
 */
class BodyLengthAdversarialTest {
    private val expectedSamples = listOf(
        Long.MIN_VALUE, -100L, -2L, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
    )
    private val copiedSamples = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)

    /** Independent restatement of the documented rule, used only as a cross-check oracle. */
    private fun referenceExpected(declared: Long, encoding: String?): Long =
        if (encoding == null || encoding.equals("identity", ignoreCase = true)) declared else -1L

    private fun referenceVerdict(expected: Long, copied: Long): BodyLength.Verdict = when {
        expected < 0L -> BodyLength.Verdict.UNVERIFIED
        copied == expected -> BodyLength.Verdict.COMPLETE
        else -> BodyLength.Verdict.TRUNCATED_OR_OVERRUN
    }

    @Test fun extremeDeclaredLengthsSurviveOnlyForNullAndIdentityEncodings() {
        assertEquals(Long.MAX_VALUE, BodyLength.expectedLength(Long.MAX_VALUE, null))
        assertEquals(Long.MIN_VALUE, BodyLength.expectedLength(Long.MIN_VALUE, "IDENTITY"))
        assertEquals(0L, BodyLength.expectedLength(0L, "iDeNtItY"))
        assertEquals(-1L, BodyLength.expectedLength(Long.MAX_VALUE, "gzip"))
        assertEquals(-1L, BodyLength.expectedLength(Long.MIN_VALUE, "br"))
    }

    @Test fun emptyWhitespaceAndMarkerBearingEncodingsAreNeverIdentity() {
        val encodings = listOf("", " ", "identity ", " identity", "identity\r\n", "gzip", marker(), "IDENTITY-")
        encodings.forEach { encoding ->
            assertEquals(-1L, BodyLength.expectedLength(Long.MAX_VALUE, encoding), "encoding [$encoding]")
        }
    }

    @Test fun expectedLengthMatchesReferenceOverAllSignedBoundaryCombinations() {
        val encodings = listOf(null, "identity", "IDENTITY", "", "gzip", " ")
        expectedSamples.forEach { declared ->
            encodings.forEach { encoding ->
                assertEquals(
                    referenceExpected(declared, encoding),
                    BodyLength.expectedLength(declared, encoding),
                    "declared=$declared encoding=$encoding",
                )
            }
        }
    }

    @Test fun verifyCleanEndMatchesReferenceOverAllSignedBoundaryCombinations() {
        expectedSamples.forEach { expected ->
            copiedSamples.forEach { copied ->
                assertEquals(
                    referenceVerdict(expected, copied),
                    BodyLength.verifyCleanEnd(expected, copied),
                    "expected=$expected copied=$copied",
                )
            }
        }
    }

    @Test fun extremeExpectedLengthIsCompleteOnlyAtTheExactCopyCount() {
        assertEquals(BodyLength.Verdict.COMPLETE, BodyLength.verifyCleanEnd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(Long.MAX_VALUE, Long.MAX_VALUE - 1))
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(0L, Long.MAX_VALUE))
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, BodyLength.verifyCleanEnd(Long.MAX_VALUE, Long.MIN_VALUE))
    }

    @Test fun everyNegativeExpectedLengthIsUnverifiedNeverTruncated() {
        listOf(Long.MIN_VALUE, -100L, -2L, -1L).forEach { expected ->
            copiedSamples.forEach { copied ->
                assertEquals(
                    BodyLength.Verdict.UNVERIFIED,
                    BodyLength.verifyCleanEnd(expected, copied),
                    "expected=$expected copied=$copied",
                )
            }
        }
    }

    @Test fun repeatedCyclesRetainNoStateAndDoNotDependOnCallHistory() {
        assertNoRetainedState(BodyLength::class.java)
        val sequence = listOf(
            Triple(Long.MAX_VALUE, "gzip", Long.MAX_VALUE),
            Triple(0L, null, 0L),
            Triple(-1L, "identity", Long.MIN_VALUE),
            Triple(Long.MIN_VALUE, null, Long.MIN_VALUE),
            Triple(10L, "gzip", 10L),
        )
        fun run(): List<Pair<Long, BodyLength.Verdict>> = sequence.map { (declared, encoding, copied) ->
            val expected = BodyLength.expectedLength(declared, encoding)
            expected to BodyLength.verifyCleanEnd(expected, copied)
        }
        val forward = run()
        sequence.asReversed().forEach { (declared, encoding, copied) ->
            BodyLength.verifyCleanEnd(BodyLength.expectedLength(declared, encoding), copied)
        }
        assertEquals(forward, run())
    }

    @Test fun hostileEncodingTextNeverThrowsOrLeaksIntoDiagnostics() {
        val lengthResult = runCatching { BodyLength.expectedLength(Long.MAX_VALUE, marker()) }
        assertTrue(lengthResult.isSuccess, "threw ${lengthResult.exceptionOrNull()?.javaClass?.simpleName}")
        assertEquals(-1L, lengthResult.getOrNull())
        val verdictResult = runCatching { BodyLength.verifyCleanEnd(Long.MAX_VALUE, Long.MIN_VALUE) }
        assertTrue(verdictResult.isSuccess, "threw ${verdictResult.exceptionOrNull()?.javaClass?.simpleName}")
        assertEquals(BodyLength.Verdict.TRUNCATED_OR_OVERRUN, verdictResult.getOrNull())
        assertTrue(!lengthResult.exceptionOrNull()?.message.orEmpty().contains(marker()))
        assertNoDiagnosticSurface(BodyLength::class.java)
    }

    /** Built from char arrays so no marker literal exists in this file or in any compiled constant pool. */
    private fun marker(): String = buildString {
        charArrayOf('s', 'y', 'n', 't', 'h', 'e', 't', 'i', 'c').forEach { append(it) }
        append('-')
        charArrayOf('m', 'a', 'r', 'k', 'e', 'r').forEach { append(it) }
    }
}

internal fun assertNoRetainedState(type: Class<*>) {
    val instanceFields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
    assertTrue(instanceFields.isEmpty(), "unexpected instance fields on ${type.simpleName}: $instanceFields")
    val accumulators = type.declaredFields.filter {
        Collection::class.java.isAssignableFrom(it.type) ||
            Map::class.java.isAssignableFrom(it.type) ||
            it.type.isArray
    }
    assertTrue(accumulators.isEmpty(), "unexpected accumulator fields on ${type.simpleName}: $accumulators")
}

internal fun assertNoDiagnosticSurface(type: Class<*>) {
    val stringMethods = type.declaredMethods.filter { !it.isSynthetic && it.returnType == String::class.java }
    assertTrue(stringMethods.isEmpty(), "unexpected String-returning method on ${type.simpleName}: $stringMethods")
    val bytes = type.getResourceAsStream("${type.simpleName}.class")!!.use { it.readBytes() }
    val lower = String(bytes, Charsets.ISO_8859_1).lowercase()
    listOf("http", "https", "ftp://", "password", "credential", "secret", "drm", "synthetic-marker")
        .forEach { token ->
            assertTrue(!lower.contains(token), "${type.simpleName} class bytes contain [$token]")
        }
}

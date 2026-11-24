package com.devtunasoft.tuneros.app53

import com.devtunasoft.tuneros.core.StreamDoctor
import com.devtunasoft.tuneros.core.StreamEvidence
import com.devtunasoft.tuneros.core.TransportFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-53 negative regressions for the shareable Stream Doctor report.
 *
 * Prior coverage is intentionally not repeated: core `StreamDoctorTest` checks diagnose() for every
 * code, C4 SEC-03 checks safeContentType() on one URL and one parameter, and C6 SEC-04 checks one
 * report (code 2004) with hostile evidence plus the MainActivity status-line formatter. This file
 * closes the remaining gaps named in APP-53: the full taxonomy through *report()* with adversarial
 * evidence, HTTP-status and content-type boundaries, combined evidence ordering, and integer
 * extremes — each checked for fixed text, a hard length cap, no private markers and determinism.
 *
 * Every marker below is synthetic. No real provider URL, credential, private path, DRM key, PIN or
 * media payload is present.
 */
class StreamDoctorShareContractTest {
    companion object {
        private const val FAKE_URL = "https://example.invalid/qa?token=SYNTHETIC_TOKEN"
        private const val FAKE_PATH = "/data/user/0/com.devtunasoft.tuneros/files/SYNTHETIC_PRIVATE"
        private const val FAKE_DRM = "SYNTHETIC_DRM_KEY_9f1c"
        private const val FAKE_PIN = "SYNTHETIC_PIN_7381"
        private const val FAKE_PAYLOAD = "SYNTHETIC_RECORDING_PAYLOAD"
        private val MARKERS = listOf(FAKE_URL, FAKE_PATH, FAKE_DRM, FAKE_PIN, FAKE_PAYLOAD)
        private const val REPORT_LIMIT = 400

        /** Hostile evidence whose every free-form field must be dropped before sharing. */
        private val HOSTILE = StreamEvidence(
            httpStatus = 404,
            contentType = "$FAKE_URL $FAKE_PATH $FAKE_DRM $FAKE_PIN $FAKE_PAYLOAD",
            transport = TransportFault.TLS,
        )

        /** Fixed expected issue per taxonomy branch, written out rather than read from production. */
        private val EXPECTED_ISSUES: Map<Int, String> = buildMap {
            put(-102, "Provider sign-in expired")
            put(-103, "Provider premium access needed")
            put(-104, "Provider stream limit")
            put(-105, "Parental restriction")
            put(-106, "Region restriction")
            put(-108, "Playback setup required")
            put(1002, "Live window moved")
            put(1003, "Connection timed out")
            put(2000, "Media input/output error")
            put(2001, "Network connection failed")
            put(2002, "Connection timed out")
            put(2003, "Unexpected content type")
            put(2004, "Stream server rejected playback")
            put(2005, "Media not found")
            put(2006, "Media access denied")
            put(2007, "Insecure HTTP blocked")
            put(2008, "Stream read position unavailable")
            put(3001, "Malformed media or guide")
            put(3002, "Malformed media or guide")
            put(3003, "Unsupported media format")
            put(3004, "Unsupported media format")
            put(4001, "Playback decoder problem")
            put(4002, "Playback decoder problem")
            put(4003, "Playback decoder problem")
            put(4004, "Unsupported media format")
            put(4005, "Unsupported media format")
            put(4006, "Playback decoder problem")
            for (code in 5001..5004) put(code, "Device audio output failed")
            for (code in 6000..6008) put(code, "Protected media issue")
        }
    }

    private fun assertNoMarkers(text: String) {
        for (marker in MARKERS) {
            assertFalse("shareable report leaked $marker", text.contains(marker, ignoreCase = true))
        }
    }

    private fun lines(text: String): List<String> =
        text.replace("\r\n", "\n").trimEnd('\n').lines()

    /**
     * Every reported branch keeps its fixed, bounded taxonomy text and drops hostile evidence, in a
     * fixed line order, without leaking markers and byte-for-byte identically on repeated calls.
     */
    @Test
    fun everyTaxonomyCodeInAReportIsFixedBoundedLeakFreeAndDeterministic() {
        for ((code, expectedIssue) in EXPECTED_ISSUES) {
            val text = StreamDoctor.report(code, HOSTILE)
            val line = lines(text)

            assertEquals("line count for $code: $line", 5, line.size)
            assertEquals("header for $code", "TunerOS stream report", line[0])
            assertEquals("code line for $code", "playback error code: $code", line[1])
            assertEquals("issue for $code", "issue: $expectedIssue", line[2])
            assertTrue("next step for $code missing", line[3].startsWith("next step: ") && line[3].length > 11)
            assertEquals("combined evidence order for $code", "detail: HTTP 404; TLS handshake failed", line[4])

            assertNoMarkers(text)
            assertTrue("report for $code exceeded $REPORT_LIMIT chars: ${text.length}", text.length <= REPORT_LIMIT)
            assertEquals("report for $code was not deterministic", text, StreamDoctor.report(code, HOSTILE))
        }
        assertEquals("taxonomy coverage changed", 40, EXPECTED_ISSUES.size)
    }

    /** Out-of-range HTTP status values are dropped, never echoed into the shareable detail. */
    @Test
    fun outOfRangeHttpStatusIsDroppedAndBoundaryStatusIsKept() {
        for (status in listOf(Int.MIN_VALUE, 99, 600, Int.MAX_VALUE)) {
            val text = StreamDoctor.report(2001, StreamEvidence(httpStatus = status))
            assertFalse("out-of-range status $status echoed", text.contains("HTTP $status"))
            assertFalse("out-of-range status $status produced a detail line", text.contains("detail:"))
            assertNoMarkers(text)
            assertEquals(text, StreamDoctor.report(2001, StreamEvidence(httpStatus = status)))
        }
        for (status in listOf(100, 599)) {
            val text = StreamDoctor.report(2001, StreamEvidence(httpStatus = status))
            assertTrue("boundary status $status dropped", text.contains("detail: HTTP $status"))
            assertTrue("boundary status $status unbounded", text.length <= REPORT_LIMIT)
        }
    }

    /** Media-type sanitizing rejects over-long and injected shapes but keeps a valid 3..64 char type/subtype. */
    @Test
    fun contentTypeLengthAndNewlineBoundariesHold() {
        val minType = "v/b"
        val maxType = "v/" + "b".repeat(62)
        val overlongType = "v/" + "b".repeat(63)
        assertEquals(minType, StreamDoctor.safeContentType(minType))
        assertEquals(maxType, StreamDoctor.safeContentType(maxType))
        assertNull("65-char media type accepted", StreamDoctor.safeContentType(overlongType))
        assertNull("newline injection accepted", StreamDoctor.safeContentType("video/mp4\nX/Y"))
        assertNull("carriage-return injection accepted", StreamDoctor.safeContentType("video/mp4\rX/Y"))
        assertEquals("video/mp4", StreamDoctor.safeContentType("video/mp4; token=$FAKE_PIN"))

        val injected = StreamDoctor.report(2003, StreamEvidence(contentType = "video/mp4\n$FAKE_URL"))
        assertEquals("injected media type changed the report shape", 4, lines(injected).size)
        assertNoMarkers(injected)
        assertTrue(injected.length <= REPORT_LIMIT)
    }

    /** The transport taxonomy is a fixed set of labels and combines after status/type in a stable order. */
    @Test
    fun transportLabelsAreFixedAndEvidenceOrderingIsStable() {
        assertEquals(
            listOf("DNS lookup failed", "connection timed out", "connection refused", "TLS handshake failed"),
            TransportFault.entries.map { it.label },
        )
        for (fault in TransportFault.entries) {
            val evidence = StreamEvidence(httpStatus = 200, contentType = "video/mp4", transport = fault)
            val text = StreamDoctor.report(2001, evidence)
            assertTrue(
                "evidence order broke for $fault",
                lines(text).last() == "detail: HTTP 200; content type video/mp4; ${fault.label}",
            )
            assertNoMarkers(text)
            assertTrue(text.length <= REPORT_LIMIT)
            assertEquals(text, StreamDoctor.report(2001, evidence))
        }
    }

    /** Unknown and integer-extreme codes fall back to the bounded generic diagnosis. */
    @Test
    fun extremeCodesStayBoundedFixedAndDeterministic() {
        for (code in listOf(Int.MIN_VALUE, -999, 9999, Int.MAX_VALUE)) {
            val text = StreamDoctor.report(code, HOSTILE)
            assertTrue("code $code missing", text.contains("playback error code: $code"))
            assertTrue("code $code lost the generic issue", text.contains("issue: Playback failed"))
            assertEquals("detail order for $code", 5, lines(text).size)
            assertNoMarkers(text)
            assertTrue("code $code unbounded", text.length <= REPORT_LIMIT)
            assertEquals(text, StreamDoctor.report(code, HOSTILE))
        }
    }
}

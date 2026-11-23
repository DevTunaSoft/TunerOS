package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial JVM coverage for the credential-free diagnostic decision table.
 *
 * Every fixture is synthetic; no provider URL, credential, DRM key or media payload appears here.
 * Expected labels are read from the fixed public contract of StreamDoctor, not from a captured run.
 * This class deliberately targets edges the basic StreamDoctorTest / StreamEvidenceTest do not assert:
 * exact range boundaries, the 3..64 content-type length window, every transport label, the
 * no-detail report shape, and canary leakage across repeated hostile calls.
 */
class StreamDoctorAdversarialTest {

    private data class Edge(val code: Int, val issue: String)

    private val labelEdges = listOf(
        Edge(Int.MIN_VALUE, "Playback failed"),
        Edge(-109, "Playback failed"),
        Edge(-108, "Playback setup required"),
        Edge(-107, "Playback failed"),
        Edge(-106, "Region restriction"),
        Edge(-105, "Parental restriction"),
        Edge(-104, "Provider stream limit"),
        Edge(-103, "Provider premium access needed"),
        Edge(-102, "Provider sign-in expired"),
        Edge(-101, "Playback failed"),
        Edge(-1, "Playback failed"),
        Edge(0, "Playback failed"),
        Edge(1001, "Playback failed"),
        Edge(1002, "Live window moved"),
        Edge(1003, "Connection timed out"),
        Edge(1004, "Playback failed"),
        Edge(2001, "Network connection failed"),
        Edge(2002, "Connection timed out"),
        Edge(2003, "Unexpected content type"),
        Edge(2005, "Media not found"),
        Edge(2007, "Insecure HTTP blocked"),
        Edge(2009, "Playback failed"),
        Edge(3000, "Playback failed"),
        Edge(3001, "Malformed media or guide"),
        Edge(3002, "Malformed media or guide"),
        Edge(3005, "Playback failed"),
        Edge(4000, "Playback failed"),
        Edge(4004, "Unsupported media format"),
        Edge(4005, "Unsupported media format"),
        Edge(4007, "Playback failed"),
        Edge(5000, "Playback failed"),
        Edge(5001, "Device audio output failed"),
        Edge(5004, "Device audio output failed"),
        Edge(5005, "Playback failed"),
        Edge(5999, "Playback failed"),
        Edge(6000, "Protected media issue"),
        Edge(6008, "Protected media issue"),
        Edge(6009, "Playback failed"),
        Edge(Int.MAX_VALUE, "Playback failed"),
    )

    @Test fun everyDecisionTableEdgeYieldsItsExactFixedLabel() {
        for (edge in labelEdges) {
            val diagnosis = StreamDoctor.diagnose(edge.code)
            assertEquals(edge.issue, diagnosis.issue, "code ${edge.code}")
            assertTrue(diagnosis.issue.length in 4..80, "code ${edge.code}")
            assertTrue(diagnosis.nextStep.length in 8..120, "code ${edge.code}")
            assertFalse(diagnosis.issue.contains("://"), "code ${edge.code}")
            assertFalse(diagnosis.nextStep.contains("://"), "code ${edge.code}")
        }
    }

    @Test fun diagnosisIsStableAndNeverEchoesTheNumericCode() {
        for (edge in labelEdges) {
            val first = StreamDoctor.diagnose(edge.code)
            assertEquals(first, StreamDoctor.diagnose(edge.code))
            assertFalse(first.issue.contains(edge.code.toString()), "code ${edge.code}")
            assertFalse(first.nextStep.contains(edge.code.toString()), "code ${edge.code}")
        }
    }

    @Test fun contentTypeLengthWindowIsInclusiveAtThreeAndSixtyFour() {
        assertEquals("a/b", StreamDoctor.safeContentType("A/B"))
        val exact = "a/" + "b".repeat(62)
        assertEquals(62, exact.length - 2)
        assertEquals(64, exact.length)
        assertEquals(exact, StreamDoctor.safeContentType(exact.uppercase()))
        assertNull(StreamDoctor.safeContentType("ab"))
        assertNull(StreamDoctor.safeContentType("a/"))
        assertNull(StreamDoctor.safeContentType("/b"))
        assertNull(StreamDoctor.safeContentType("a/" + "b".repeat(63)))
    }

    @Test fun contentTypeAcceptsOnlyTheClosedSafeCharacterSet() {
        assertEquals("video/h264", StreamDoctor.safeContentType("VIDEO/H264"))
        assertEquals("video/x-matroska.5+foo", StreamDoctor.safeContentType("video/x-matroska.5+foo"))
        assertNull(StreamDoctor.safeContentType("video//h264"))
        assertNull(StreamDoctor.safeContentType("video/h264/extra"))
        assertNull(StreamDoctor.safeContentType("video/h264\nset-cookie: 1"))
        assertNull(StreamDoctor.safeContentType("video/h264\u0000"))
    }

    @Test fun contentTypeNeverReturnsCanaryOrAddressMaterial() {
        val hostile = listOf(
            "text/html?token=SYNTHETIC_CANARY",
            "text/html\r\nX-Token: SYNTHETIC_CANARY",
            "http://fixture.invalid/live.m3u8?token=SYNTHETIC_CANARY",
            "text/html user:pass@fixture.invalid",
        )
        for (value in hostile) assertNull(StreamDoctor.safeContentType(value), value)
    }

    @Test fun everyTransportLabelIsFixedText() {
        assertEquals("DNS lookup failed", StreamDoctor.describe(StreamEvidence(transport = TransportFault.DNS)))
        assertEquals("connection timed out", StreamDoctor.describe(StreamEvidence(transport = TransportFault.TIMEOUT)))
        assertEquals("connection refused", StreamDoctor.describe(StreamEvidence(transport = TransportFault.REFUSED)))
        assertEquals("TLS handshake failed", StreamDoctor.describe(StreamEvidence(transport = TransportFault.TLS)))
    }

    @Test fun droppedStatusStillKeepsSafeTypeAndTransportInFixedOrder() {
        assertEquals(
            "content type text/html; connection refused",
            StreamDoctor.describe(
                StreamEvidence(httpStatus = 99, contentType = "Text/HTML; charset=utf-8", transport = TransportFault.REFUSED),
            ),
        )
        assertEquals(
            "content type text/html",
            StreamDoctor.describe(StreamEvidence(httpStatus = 600, contentType = "text/html")),
        )
        assertEquals(
            "TLS handshake failed",
            StreamDoctor.describe(
                StreamEvidence(httpStatus = 0, contentType = "http://fixture.invalid/x", transport = TransportFault.TLS),
            ),
        )
        assertEquals("", StreamDoctor.describe(StreamEvidence()))
    }

    @Test fun reportWithoutEvidenceOmitsTheDetailLine() {
        val text = StreamDoctor.report(2001, StreamEvidence())
        assertEquals("TunerOS stream report", text.lines().first())
        assertTrue(text.contains("playback error code: 2001"), text)
        assertTrue(text.contains("issue: Network connection failed"), text)
        assertTrue(text.contains("next step: Check internet access and stream availability"), text)
        assertFalse(text.contains("detail:"), text)
        assertTrue(text.length <= 400)
    }

    @Test fun reportWithValidEvidenceHasExactlyOneBoundedDetailLine() {
        val text = StreamDoctor.report(2004, StreamEvidence(httpStatus = 404, contentType = "text/html"))
        assertEquals(1, text.lines().count { it.startsWith("detail:") })
        assertTrue(text.contains("detail: HTTP 404; content type text/html"), text)
        assertTrue(text.length <= 400)
    }

    @Test fun hostileEvidenceNeverLeaksCanaryIntoARepeatedBoundedReport() {
        val evidence = StreamEvidence(
            httpStatus = 599,
            contentType = "text/html?token=SYNTHETIC_CANARY",
            transport = TransportFault.TLS,
        )
        val first = StreamDoctor.report(2004, evidence)
        assertFalse(first.contains("SYNTHETIC_CANARY"), first)
        assertFalse(first.contains("token"), first)
        assertFalse(first.contains("://"), first)
        assertTrue(first.length <= 400)
        assertTrue(first.lines().all { it.length <= 120 })
        repeat(200) {
            assertEquals(first, StreamDoctor.report(2004, evidence))
            assertNull(StreamDoctor.safeContentType(evidence.contentType))
        }
    }
}

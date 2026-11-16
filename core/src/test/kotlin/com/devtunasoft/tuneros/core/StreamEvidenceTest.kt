package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamEvidenceTest {
    @Test fun describesStatusThenContentTypeThenTransport() {
        assertEquals(
            "HTTP 404; content type text/html",
            StreamDoctor.describe(StreamEvidence(httpStatus = 404, contentType = "text/html")),
        )
        assertEquals(
            "connection timed out",
            StreamDoctor.describe(StreamEvidence(transport = TransportFault.TIMEOUT)),
        )
        assertEquals(
            "HTTP 403; DNS lookup failed",
            StreamDoctor.describe(StreamEvidence(httpStatus = 403, transport = TransportFault.DNS)),
        )
        assertEquals("", StreamDoctor.describe(StreamEvidence()))
    }

    @Test fun dropsStatusesOutsideTheHttpRange() {
        assertEquals("", StreamDoctor.describe(StreamEvidence(httpStatus = 0)))
        assertEquals("", StreamDoctor.describe(StreamEvidence(httpStatus = 99)))
        assertEquals("", StreamDoctor.describe(StreamEvidence(httpStatus = 600)))
        assertEquals("HTTP 100", StreamDoctor.describe(StreamEvidence(httpStatus = 100)))
        assertEquals("HTTP 599", StreamDoctor.describe(StreamEvidence(httpStatus = 599)))
    }

    @Test fun keepsOnlyBareLowercaseMediaTypes() {
        assertEquals("text/html", StreamDoctor.safeContentType("Text/HTML; charset=utf-8"))
        assertEquals("video/mp2t", StreamDoctor.safeContentType(" video/MP2T "))
        assertEquals("application/vnd.apple.mpegurl", StreamDoctor.safeContentType("application/vnd.apple.mpegurl"))
        assertNull(StreamDoctor.safeContentType(null))
        assertNull(StreamDoctor.safeContentType("html"))
        assertNull(StreamDoctor.safeContentType("/html"))
        assertNull(StreamDoctor.safeContentType("text/"))
        assertNull(StreamDoctor.safeContentType("a/b/c"))
        assertNull(StreamDoctor.safeContentType("x".repeat(70)))
    }

    @Test fun neverCarriesUrlsOrCredentialsWhateverTheInput() {
        val hostile = StreamEvidence(httpStatus = 403, contentType = "text/html?token=abc", transport = TransportFault.DNS)
        assertEquals("HTTP 403; DNS lookup failed", StreamDoctor.describe(hostile))
        for (value in listOf("http://provider.example/live.m3u8", "text/html user:pass@host", "text/html\nset-cookie")) {
            assertNull(StreamDoctor.safeContentType(value))
        }
        val longType = "x".repeat(40) + "/" + "x".repeat(40)
        val bounded = StreamDoctor.describe(StreamEvidence(httpStatus = 599, contentType = longType, transport = TransportFault.TLS))
        assertTrue(bounded.length <= 120)
        assertFalse(StreamDoctor.describe(StreamEvidence(contentType = "http://a/b")).contains("http"))
    }
}

package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CORE-42: deterministic coverage of the M3U shared-header lifecycle seam.
 *
 * Scope: what happens to a channel's #EXTVLCOPT user-agent/referer when its own entry is
 * rejected (unsupported URL, blank name, duplicate identity) and a later entry is accepted.
 * A provider header must never survive a rejected entry: the next channel could otherwise
 * receive another provider's request headers.
 *
 * Excluded as already owned: CORE-27 covers caps, repeated cycles, line endings, orphan header
 * lines, attribute scanning and generic diagnostics; CORE-04 covers large bounded feeds;
 * CoreTest covers legacy IDs and clipping. Every fixture here is synthetic and small.
 */
class PlaylistHeaderLifecycleRegressionTest {
    private companion object {
        const val AGENT = "SyntheticAgentA"
        const val REFERRER = "SyntheticReferrerA"
    }

    private fun headers(url: String): String =
        "#EXTINF:-1,Rejected\n" +
            "#EXTVLCOPT:http-user-agent=$AGENT\n" +
            "#EXTVLCOPT:http-referrer=$REFERRER\n" +
            "$url\n"

    @Test fun unsupportedUrlEntryDoesNotCarryItsHeadersToTheNextChannel() {
        val feed = headers("file:///synthetic.invalid/local") +
            "#EXTINF:-1,Accepted\nhttps://example.org/accepted\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals(listOf("Accepted"), channels.map { it.name })
        assertEquals(null, channels.single().userAgent)
        assertEquals(null, channels.single().referer)
    }

    @Test fun blankNameEntryDoesNotCarryItsHeadersToTheNextChannel() {
        val feed = "#EXTINF:-1,\n" +
            "#EXTVLCOPT:http-user-agent=$AGENT\n" +
            "#EXTVLCOPT:http-referer=$REFERRER\n" +
            "https://example.org/blank-name\n" +
            "#EXTINF:-1,Accepted\nhttps://example.org/accepted\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals(listOf("Accepted"), channels.map { it.name })
        assertEquals(null, channels.single().userAgent)
        assertEquals(null, channels.single().referer)
    }

    @Test fun duplicateEntryDoesNotCarryItsHeadersToTheNextChannel() {
        val feed = "#EXTINF:-1,First\nhttps://example.org/first\n" +
            "#EXTINF:-1,Duplicate\n" +
            "#EXTVLCOPT:http-user-agent=$AGENT\n" +
            "https://example.org/first\n" +
            "#EXTINF:-1,Second\nhttps://example.org/second\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals(listOf("First", "Second"), channels.map { it.name })
        assertEquals(null, channels[0].userAgent)
        assertEquals(null, channels[1].userAgent)
    }

    @Test fun headersOfAnAcceptedEntryStayWithThatEntryOnly() {
        val feed = "#EXTINF:-1,One\n" +
            "#EXTVLCOPT:http-user-agent=AgentOne\n" +
            "#EXTVLCOPT:http-referer=RefererOne\n" +
            "https://example.org/one\n" +
            "#EXTINF:-1,Two\nhttps://example.org/two\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals("AgentOne", channels[0].userAgent)
        assertEquals("RefererOne", channels[0].referer)
        assertEquals(null, channels[1].userAgent)
        assertEquals(null, channels[1].referer)
    }

    @Test fun theNextEntryOwnHeaderReplacesThePreviousOne() {
        val feed = "#EXTINF:-1,One\n#EXTVLCOPT:http-user-agent=AgentOne\nhttps://example.org/one\n" +
            "#EXTINF:-1,Two\n#EXTVLCOPT:http-user-agent=AgentTwo\n" +
            "#EXTVLCOPT:http-referer=RefererTwo\nhttps://example.org/two\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals("AgentOne", channels[0].userAgent)
        assertEquals(null, channels[0].referer)
        assertEquals("AgentTwo", channels[1].userAgent)
        assertEquals("RefererTwo", channels[1].referer)
    }

    @Test fun headerValuesStayBoundedAndDiagnosticsNeverEchoHeaderMaterial() {
        val longAgent = "A".repeat(600)
        val longReferer = "R".repeat(2200)
        val feed = "#EXTINF:-1,One\n" +
            "#EXTVLCOPT:http-user-agent=$longAgent\n" +
            "#EXTVLCOPT:http-referer=$longReferer\n" +
            "https://example.org/one\n" +
            "#EXTINF:-1,Two\nhttps://example.org/two\n"
        val report = PlaylistParser.parseReport(feed.reader(), maxChannels = 1)
        assertEquals(512, report.channels.single().userAgent!!.length)
        assertEquals(2048, report.channels.single().referer!!.length)
        assertTrue(report.warnings.isNotEmpty())
        report.warnings.forEach { warning ->
            assertFalse(warning.contains(longAgent.take(16)))
            assertFalse(warning.contains(longReferer.take(16)))
        }
    }
}

package com.devtunasoft.tuneros.core

import java.io.BufferedReader
import java.io.StringReader
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collection
import java.util.Map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CORE-27: deterministic adversarial coverage of the M3U parsing seam.
 *
 * CORE-04 already covers one-shot linear retention over large feeds and five repeated large parses; CoreTest covers
 * oversized metadata clipping from far-above-cap values, legacy ID hashing and unused-attribute handling;
 * PlaylistAttributeScanRegressionTest covers the quadratic attribute scan; BoundedLinesFuzzTest covers raw line
 * boundaries including CR. This file deliberately targets the remaining seams: exact caps, adversarial repeated
 * cycles over mixed malformed input, caller-stream ownership, line-ending equivalence at the parser seam,
 * blank/duplicate IDs, and generic diagnostics under marker-bearing input.
 *
 * All fixtures are synthetic. Marker bytes are assembled at run time so no credential-like token appears as a literal
 * in this class or in any diagnostic. The parser never emits provider URLs; warnings contain only the fixed cap text.
 */
class PlaylistAdversarialTest {
    private companion object {
        const val GUIDE_ID_CAP = 256
        const val NAME_CAP = 256
        const val ATTRIBUTE_KEY_CAP = 64
        const val GROUP_CAP = 128
        const val LOGO_CAP = 2048
    }

    /** Synthetic marker built from chars: it never exists in this class's constant pool. */
    private fun syntheticMarker(): String =
        String(charArrayOf('S', 'Y', 'N', 'T', 'H', 'C', 'R', 'E', 'D', '9', 'F', '3', 'A', '2', 'C', '7', '1'))

    /** Independent legacy oracle: the ID hashes the full provider guide ID, never the clipped copy. */
    private fun legacyId(sourceId: String, guideId: String, url: String): String {
        val key = sourceId + "\u0000" + guideId + "\u0000" + url
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }

    private fun metadataFeed(id: String, logo: String, name: String, group: String, url: String): String =
        "#EXTINF:-1 tvg-id=\"$id\" tvg-logo=\"$logo\" group-title=\"$group\",$name\n$url\n"

    @Test fun exactLineCapIsAcceptedAndOneExtraCharacterFailsWithoutEchoingInput() {
        val urlPrefix = "https://example.org/"
        val url = urlPrefix + "x".repeat(PlaylistParser.MAX_LINE_LENGTH - urlPrefix.length)
        assertEquals(PlaylistParser.MAX_LINE_LENGTH, url.length)

        val accepted = PlaylistParser.parse("#EXTINF:-1,Name\n$url\n".reader())
        assertEquals(url, accepted.single().streamUrl)

        val marker = syntheticMarker()
        val rejected = "#EXTINF:-1,Name\n$url${marker[0]}\n".reader()
        val failure = assertFailsWith<IllegalArgumentException> { PlaylistParser.parse(rejected) }
        assertEquals("Playlist line exceeds ${PlaylistParser.MAX_LINE_LENGTH} characters", failure.message)
        assertFalse(failure.message.orEmpty().contains(marker))
    }

    @Test fun maxChannelArgumentsFailClosedAtBothEndsWithoutEchoingMarkers() {
        val feed = "#EXTINF:-1,One\nhttps://example.org/one\n"
        assertEquals(1, PlaylistParser.parseReport(feed.reader(), 1).channels.size)
        assertEquals(
            1,
            PlaylistParser.parseReport(feed.reader(), PlaylistParser.MAX_CHANNELS).channels.size,
        )
        val marker = syntheticMarker()
        for (bad in listOf(0, -1, Int.MIN_VALUE, PlaylistParser.MAX_CHANNELS + 1, Int.MAX_VALUE)) {
            val failure = assertFailsWith<IllegalArgumentException>("maxChannels=$bad") {
                PlaylistParser.parseReport(feed.reader(), bad, sourceId = marker)
            }
            assertFalse(failure.message.orEmpty().contains(marker), "maxChannels=$bad")
        }
    }

    @Test fun metadataClippingBoundariesKeepOriginalGuideIdentity() {
        val exactId = "g".repeat(GUIDE_ID_CAP)
        val longId = exactId + "Z"
        val logoPrefix = "https://example.org/"
        val exactLogo = logoPrefix + "l".repeat(LOGO_CAP - logoPrefix.length)
        val longLogo = exactLogo + "Z"
        val exactName = "N".repeat(NAME_CAP)
        val longName = exactName + "Z"
        val exactGroup = "G".repeat(GROUP_CAP)
        val longGroup = exactGroup + "Z"
        val exactUrl = "https://example.org/exact"
        val longUrl = "https://example.org/long"

        val exact = PlaylistParser.parse(
            metadataFeed(exactId, exactLogo, exactName, exactGroup, exactUrl).reader(),
        ).single()
        val long = PlaylistParser.parse(
            metadataFeed(longId, longLogo, longName, longGroup, longUrl).reader(),
        ).single()

        assertEquals(exactId, exact.guideId)
        assertEquals(exactId, long.guideId)
        assertEquals(GUIDE_ID_CAP, long.guideId!!.length)
        assertEquals(legacyId("default", exactId, exactUrl), exact.id)
        assertEquals(legacyId("default", longId, longUrl), long.id)

        assertEquals(exactLogo, exact.logoUrl)
        assertEquals(null, long.logoUrl)
        assertEquals(exactName, exact.name)
        assertEquals(exactName, long.name)
        assertEquals(NAME_CAP, long.name.length)
        assertEquals(exactGroup, exact.group)
        assertEquals(exactGroup, long.group)
        assertEquals(GROUP_CAP, long.group!!.length)
    }

    /** CRLF, an unterminated final line, duplicates and malformed URLs in one adversarial cycle. */
    private val mixedFeed = buildString {
        append("#EXTM3U\r\n")
        append("#EXTINF:-1 tvg-id=\"\",First\r\nhttps://example.org/a\r\n")
        append("#EXTINF:-1 tvg-id=\"\",Duplicate URL\r\nhttps://example.org/a\r\n")
        append("#EXTINF:-1 tvg-id=\"shared\",Shared A\r\nhttps://example.org/b\r\n")
        append("#EXTINF:-1 tvg-id=\"shared\",Shared B\r\nhttps://example.org/c\r\n")
        append("#EXTINF:-1,Rejected\r\nfile:///synthetic.invalid/local\r\n")
        append("#EXTINF:-1,Third\r\n")
        append("#EXTVLCOPT:http-user-agent=SyntheticAgent\r\nhttps://example.org/d")
    }

    @Test fun repeatedAdversarialCyclesReturnIdenticalBoundedState() {
        val expectedNames = listOf("First", "Shared A", "Shared B", "Third")
        val first = PlaylistParser.parseReport(mixedFeed.reader(), sourceId = "cycle-check")
        assertEquals(expectedNames, first.channels.map { it.name })
        assertTrue(first.warnings.isEmpty())
        assertEquals("SyntheticAgent", first.channels.last().userAgent)
        assertEquals(4, first.channels.map { it.id }.toSet().size)

        repeat(40) { cycle ->
            val next = PlaylistParser.parseReport(mixedFeed.reader(), sourceId = "cycle-check")
            assertEquals(first.channels.map { it.id }, next.channels.map { it.id }, "cycle $cycle")
            assertEquals(first.channels.map { it.storageKey }, next.channels.map { it.storageKey }, "cycle $cycle")
            assertEquals(first.channels.map { it.name }, next.channels.map { it.name }, "cycle $cycle")
            assertEquals(first.warnings, next.warnings, "cycle $cycle")
        }

        val fields = PlaylistParser::class.java.declaredFields
        assertTrue(
            fields.none { !Modifier.isStatic(it.modifiers) },
            "PlaylistParser must keep no instance state between cycles",
        )
        assertTrue(
            fields.none {
                Collection::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type)
            },
            "PlaylistParser must not retain a collection or map across cycles",
        )
    }

    @Test fun callerReaderRemainsOpenAfterParse() {
        var closed = false
        val reader = object : StringReader("#EXTINF:-1,Name\nhttps://example.org/one\n") {
            override fun close() {
                closed = true
                super.close()
            }
        }
        assertEquals("Name", PlaylistParser.parse(BufferedReader(reader)).single().name)
        assertFalse(closed, "The caller owns the reader and must be able to keep using it")
    }

    @Test fun lineEndingVariantsProduceIdenticalImports() {
        val lf = "#EXTM3U\n#EXTINF:-1,A\nhttps://example.org/a\n#EXTINF:-1,B\nhttps://example.org/b\n"
        val variants = listOf(
            lf,
            lf.replace("\n", "\r\n"),
            lf.removeSuffix("\n"),
            lf.removeSuffix("\n") + "\r",
        )
        val reports = variants.map { PlaylistParser.parseReport(it.reader()) }
        assertEquals(listOf("A", "B"), reports.first().channels.map { it.name })
        reports.drop(1).forEachIndexed { index, report ->
            assertEquals(reports.first().channels.map { it.id }, report.channels.map { it.id }, "variant $index")
            assertEquals(reports.first().warnings, report.warnings, "variant $index")
        }
    }

    @Test fun orphanMetadataAndHeaderLinesAreDiscarded() {
        assertTrue(PlaylistParser.parse("#EXTM3U\n#EXTINF:-1,Orphan\n".reader()).isEmpty())
        val headerOnly = "#EXTVLCOPT:http-user-agent=A\n#EXTVLCOPT:http-referrer=R\nhttps://example.org/x\n"
        assertTrue(PlaylistParser.parse(headerOnly.reader()).isEmpty())
        assertTrue(PlaylistParser.parse("".reader()).isEmpty())
    }

    @Test fun longMalformedAttributeListStillYieldsConsumedFields() {
        val junk = (0 until 400).joinToString(" ") { "k".repeat(ATTRIBUTE_KEY_CAP) + "-$it=x" }
        val feed = "#EXTINF:-1 $junk tvg-id=\"guide-x\" tvg-name=\"Fallback\" group-title='News',Shown\n" +
            "https://example.org/attributes\n"
        val channel = PlaylistParser.parse(feed.reader()).single()
        assertEquals("guide-x", channel.guideId)
        assertEquals("Shown", channel.name)
        assertEquals("News", channel.group)
    }

    @Test fun blankAndDuplicateGuideIdsKeepChannelAndStorageKeysDistinct() {
        val feed = "#EXTINF:-1 tvg-id=\"\",Alpha\nhttps://example.org/alpha\n" +
            "#EXTINF:-1 tvg-id=\"   \",Beta\nhttps://example.org/beta\n" +
            "#EXTINF:-1 tvg-id=\"shared\",One\nhttps://example.org/one\n" +
            "#EXTINF:-1 tvg-id=\"shared\",Two\nhttps://example.org/two\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals(listOf("Alpha", "Beta", "One", "Two"), channels.map { it.name })
        assertEquals(4, channels.map { it.id }.toSet().size)
        assertEquals(4, channels.map { it.storageKey }.toSet().size)
        assertNotEquals(channels[0].storageKey, channels[1].storageKey)
    }

    @Test fun malformedUrlsFailClosedWithoutThrowingOrEmittingWarnings() {
        val rejected = listOf(
            "file:///synthetic.invalid/local",
            "ftp://example.org/x",
            "https:///missing-host",
            "https://example.org/a\u0001b",
            "https://example.org/\u007ftoken",
            "not a url",
            "",
        )
        rejected.forEach { url ->
            val report = PlaylistParser.parseReport("#EXTINF:-1,Name\n$url\n".reader())
            assertTrue(report.channels.isEmpty(), "url was accepted: ${url.length} chars")
            assertTrue(report.warnings.isEmpty(), "url produced a warning: ${url.length} chars")
        }
    }

    @Test fun lineCountCapFailsClosedWithGenericMessage() {
        val marker = syntheticMarker()
        val feed = buildString {
            append(marker).append('\n')
            repeat(200_000) { append("x\n") }
        }
        val failure = assertFailsWith<IllegalArgumentException> { PlaylistParser.parse(feed.reader()) }
        assertEquals("Playlist exceeds 200000 lines", failure.message)
        assertFalse(failure.message.orEmpty().contains(marker))
    }

    @Test fun warningsNeverEchoSourceOrProviderMarkers() {
        val marker = syntheticMarker()
        val feed = (0 until 3).joinToString("") { index ->
            "#EXTINF:-1,Channel$index\nhttps://example.org/$marker-$index\n"
        }
        val report = PlaylistParser.parseReport(feed.reader(), maxChannels = 1, sourceId = marker)
        assertEquals(1, report.channels.size)
        assertEquals(listOf("2 channels omitted: limit 1"), report.warnings)
        report.warnings.forEach { assertFalse(it.contains(marker)) }
    }
}

package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class CoreTest {
    @Test fun playlistMetadataAndHeaders() {
        val text = """#EXTM3U
#EXTINF:-1 tvg-id="news.us" tvg-logo="https://example.com/logo.png" group-title="News",News HD
#EXTVLCOPT:http-user-agent=TestAgent
https://example.com/live.m3u8
"""
        val channels = PlaylistParser.parse(text.reader())
        assertEquals(1, channels.size)
        assertEquals("news.us", channels.single().guideId)
        assertEquals("News", channels.single().group)
        assertEquals("TestAgent", channels.single().userAgent)
    }
    @Test fun unsupportedAndUnpairedUrlsAreRejected() {
        assertEquals(0, PlaylistParser.parse("#EXTINF:-1,Bad\nfile:///secret\n".reader()).size)
        assertEquals(0, PlaylistParser.parse("https://example.com/no-metadata".reader()).size)
    }
    @Test fun boundChannels() {
        val lines = "#EXTINF:-1,One\nhttps://example.com/one\n#EXTINF:-1,Two\nhttps://example.com/two\n"
        val report = PlaylistParser.parseReport(lines.reader(), 1)
        assertEquals(1, report.channels.size)
        assertTrue(report.warnings.single().contains("1 channels omitted"))
    }
    @Test fun xmlGuideParsing() {
        val xml = """<tv><channel id="news.us"><display-name>News</display-name></channel><programme channel="news.us" start="20261108100000 -0600" stop="20261108110000 -0600"><title>Morning News</title></programme></tv>"""
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals("News", guide.channelNames["news.us"])
        assertEquals("Morning News", guide.programs.single().title)
        assertEquals(guide.programs.single(), guide.now("news.us", guide.programs.single().startEpochSeconds + 1))
    }
    @Test fun futureEpgCountsAsScheduleEvenWithoutCurrentProgram() {
        val future = GuideProgram("news.us", "Tomorrow", 1_000_000L, 1_003_600L)
        val guide = GuideSnapshot(listOf(future), mapOf("news.us" to "News"))
        guide.prepareIndex()
        assertEquals(true, guide.hasSchedule("news.us"))
        assertEquals(false, guide.hasSchedule("missing"))
        assertEquals(null, guide.now("news.us", 999_999L))
        assertEquals(future, guide.now("news.us", 1_000_001L))
    }
    @Test fun xxeIsBlocked() {
        val xml = "<!DOCTYPE tv [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><tv><channel id=\"a\"><display-name>&xxe;</display-name></channel></tv>"
        assertFailsWith<Exception> { GuideParser.parse(ByteArrayInputStream(xml.toByteArray())) }
    }
    @Test fun invalidProgramIsIgnored() {
        val xml = """<tv><programme channel="x" start="bad" stop="20261108110000 -0600"><title>Ignored</title></programme></tv>"""
        assertEquals(0, GuideParser.parse(ByteArrayInputStream(xml.toByteArray())).programs.size)
    }

    @Test fun duplicateGuideIdFromDifferentStreamsDoesNotCollide() {
        val feed = "#EXTINF:-1 tvg-id=\"shared\",First\nhttps://example.org/one\n" +
            "#EXTINF:-1 tvg-id=\"shared\",Second\nhttps://example.org/two\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals(2, channels.size)
        assertNotEquals(channels[0].id, channels[1].id)
        val otherSource = PlaylistParser.parseReport(feed.reader(), sourceId = "another")
        assertNotEquals(channels[0].id, otherSource.channels[0].id)
    }
    @Test fun hugeLineIsRejectedByBoundedReader() {
        val input = "#EXTM3U\n" + "X".repeat(PlaylistParser.MAX_LINE_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> { PlaylistParser.parse(input.reader()) }
    }
    @Test fun unsortedOverlappingGuideFindsLatestActiveEntry() {
        val long = GuideProgram("c", "Long", 0, 1000)
        val short = GuideProgram("c", "Short", 50, 60)
        val later = GuideProgram("c", "Later", 200, 300)
        val guide = GuideSnapshot(listOf(later, short, long), emptyMap())
        guide.prepareIndex()
        assertEquals(short, guide.now("c", 55))
        assertEquals(long, guide.now("c", 75))
        assertEquals(later, guide.now("c", 250))
        assertEquals(null, guide.now("c", 1001))
        assertEquals(long, guide.currentProgramsAt(75)["c"])
    }
    @Test fun timezoneMissingDefaultsToUtcAndWarns() {
        val xml = """<tv><programme channel="c" start="20261108100000" stop="20261108110000"><title>UTC</title></programme></tv>"""
        val report = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
        val expected = LocalDateTime.of(2026, 11, 8, 10, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals(expected, report.programs.single().startEpochSeconds)
        assertTrue(report.warnings.any { it.contains("assumed UTC") })
    }
    @Test fun xmltvProgrammeCapWarnsWithoutDiscardingGuide() {
        val xml = """<tv><channel id="a"><display-name>A</display-name></channel><channel id="b"><display-name>B</display-name></channel><programme channel="a" start="20261108100000 +0000" stop="20261108110000 +0000"><title>A</title></programme><programme channel="b" start="20261108110000 +0000" stop="20261108120000 +0000"><title>B</title></programme></tv>"""
        val report = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()), maxPrograms = 1, maxChannels = 1)
        assertEquals(1, report.programs.size)
        assertEquals(1, report.channelNames.size)
        assertTrue(report.warnings.any { it.contains("1 programmes omitted") })
        assertTrue(report.warnings.any { it.contains("1 channel names omitted") })
    }
    private class StubConnection(url: URL, private val status: Int = 200,
                                 private val body: ByteArray = byteArrayOf(),
                                 private val location: String? = null,
                                 private val encoding: String? = null) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getContentEncoding(): String? = encoding
    }
    @Test fun gzipWithoutHeadersIsDetectedByMagicBytes() {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("guide".toByteArray()) }
        }.toByteArray()
        val loader = SourceDownloader { StubConnection(it, body = compressed) }
        assertEquals("guide", loader.open("https://example.org/guide", 100).bufferedReader().use { it.readText() })
    }
    @Test fun decompressedByteLimitIsEnforced() {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("A".repeat(100).toByteArray()) }
        }.toByteArray()
        val loader = SourceDownloader { StubConnection(it, body = compressed) }
        assertFailsWith<IllegalArgumentException> {
            loader.open("https://example.org/guide", 10).use { it.readBytes() }
        }
    }
    @Test fun httpsRedirectCannotDowngrade() {
        val loader = SourceDownloader { StubConnection(it, status = 302, location = "http://example.org/feed") }
        assertFailsWith<IllegalArgumentException> { loader.open("https://example.org/feed", 100) }
    }
    @Test fun httpRedirectCanUpgradeToHttps() {
        val loader = SourceDownloader {
            if (it.protocol == "http") StubConnection(it, status = 301, location = "https://example.org/feed")
            else StubConnection(it, body = "ok".toByteArray())
        }
        assertEquals("ok", loader.open("http://example.org/feed", 100).bufferedReader().use { it.readText() })
    }
    @Test fun malformedSourceUrlsAreRejected() {
        assertFailsWith<IllegalArgumentException> { SourceDownloader.checkedUrl("file:///etc/passwd") }
        assertFailsWith<IllegalArgumentException> { SourceDownloader.checkedUrl("https://") }
    }
    @Test fun multiviewRespectsBothLimits() {
        assertEquals(2, MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(4, 2)))
        assertEquals(0, MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(0, 4)))
        assertEquals(1, MultiviewPolicy.chooseAudioTile(1, 3))
        assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(3, 3) }
    }
}

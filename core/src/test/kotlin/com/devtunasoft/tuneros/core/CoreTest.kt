package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        assertFailsWith<IllegalArgumentException> { PlaylistParser.parse(lines.reader(), 1) }
    }
    @Test fun xmlGuideParsing() {
        val xml = """<tv><channel id="news.us"><display-name>News</display-name></channel><programme channel="news.us" start="20261108100000 -0600" stop="20261108110000 -0600"><title>Morning News</title></programme></tv>"""
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals("News", guide.channelNames["news.us"])
        assertEquals("Morning News", guide.programs.single().title)
        assertEquals(guide.programs.single(), guide.now("news.us", guide.programs.single().startEpochSeconds + 1))
    }
    @Test fun xxeIsBlocked() {
        val xml = "<!DOCTYPE tv [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><tv><channel id=\"a\"><display-name>&xxe;</display-name></channel></tv>"
        assertFailsWith<Exception> { GuideParser.parse(ByteArrayInputStream(xml.toByteArray())) }
    }
    @Test fun invalidProgramIsIgnored() {
        val xml = """<tv><programme channel="x" start="bad" stop="20261108110000 -0600"><title>Ignored</title></programme></tv>"""
        assertEquals(0, GuideParser.parse(ByteArrayInputStream(xml.toByteArray())).programs.size)
    }
    @Test fun multiviewRespectsBothLimits() {
        assertEquals(2, MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(4, 2)))
        assertEquals(0, MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(0, 4)))
        assertEquals(1, MultiviewPolicy.chooseAudioTile(1, 3))
        assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(3, 3) }
    }
}

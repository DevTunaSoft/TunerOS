package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** XMLTV channel IDs are often repeated hundreds of thousands of times. */
class GuideChannelIdPoolingTest {
    private fun parse(xml: String, channels: Int = 25_000): GuideSnapshot =
        GuideParser.parse(ByteArrayInputStream(xml.toByteArray()), maxChannels = channels)

    @Test fun programmesAndChannelMetadataReuseOneCanonicalIdentifier() {
        val guide = parse("""<tv>
            <channel id="provider.sports"><display-name>Sports</display-name></channel>
            <programme channel="provider.sports" start="20261113100000 +0000"
                stop="20261113110000 +0000"><title>Morning</title></programme>
            <programme channel="provider.sports" start="20261113110000 +0000"
                stop="20261113120000 +0000"><title>Afternoon</title></programme>
        </tv>""")
        assertSame(guide.programs[0].channelId, guide.programs[1].channelId)
        assertSame(guide.channelNames.keys.single(), guide.programs[0].channelId)
    }

    @Test fun programmesBeforeChannelDeclarationStillShareTheIdentifier() {
        val guide = parse("""<tv>
            <programme channel="late.provider" start="20261113100000 +0000"
                stop="20261113110000 +0000"><title>First</title></programme>
            <programme channel="late.provider" start="20261113110000 +0000"
                stop="20261113120000 +0000"><title>Second</title></programme>
            <channel id="late.provider"><display-name>Late arrival</display-name></channel>
        </tv>""")
        assertEquals("Late arrival", guide.channelNames["late.provider"])
        assertSame(guide.programs[0].channelId, guide.programs[1].channelId)
        assertSame(guide.channelNames.keys.single(), guide.programs[0].channelId)
    }

    @Test fun boundedPoolDoesNotDropProgramsAfterUniqueChannelCap() {
        val xml = buildString {
            append("<tv><channel id=\"a\"><display-name>A</display-name></channel>")
            repeat(200) { i ->
                append("<programme channel=\"")
                append(if (i % 2 == 0) "a" else "unique-$i")
                append("\" start=\"20261113100000 +0000\"")
                append(" stop=\"20261113110000 +0000\"><title>Show</title></programme>")
            }
            append("</tv>")
        }
        val guide = parse(xml, channels = 1)
        assertEquals(200, guide.programs.size)
        assertEquals(1, guide.channelNames.size)
        assertEquals("a", guide.programs.first().channelId)
        assertEquals("unique-199", guide.programs.last().channelId)
        assertSame(guide.channelNames.keys.single(), guide.programs[198].channelId)
    }
}

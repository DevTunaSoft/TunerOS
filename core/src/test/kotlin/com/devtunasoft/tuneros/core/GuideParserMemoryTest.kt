package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Large XMLTV text must not be retained in memory for every programme. */
class GuideParserMemoryTest {
    @Test fun longFieldsAreBoundedAndReportTruncation() {
        val title = "T".repeat(10_000)
        val name = "N".repeat(10_000)
        val desc = "D".repeat(10_000)
        val xml = """<tv>
            <channel id="c"><display-name>$name</display-name></channel>
            <programme channel="c" start="20261111100000 +0000" stop="20261111110000 +0000">
                <title>$title</title><desc>$desc</desc>
            </programme>
        </tv>"""
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(GuideParser.MAX_TITLE_CHARS, guide.programs.single().title.length)
        assertEquals(GuideParser.MAX_CHANNEL_NAME_CHARS, guide.channelNames.getValue("c").length)
        assertEquals(GuideParser.MAX_DESCRIPTION_CHARS, guide.programs.single().description?.length)
        assertTrue(guide.warnings.any { it.contains("programme titles shortened") })
        assertTrue(guide.warnings.any { it.contains("channel names shortened") })
        assertTrue(guide.warnings.any { it.contains("descriptions shortened") })
    }

    @Test fun descriptionOptOutDoesNotRetainTextOrWarnAboutIgnoredFields() {
        val xml = """<tv><programme channel="c" start="20261111100000 +0000"
            stop="20261111110000 +0000"><title>News</title>
            <desc>${"D".repeat(10_000)}</desc></programme></tv>"""
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()), includeDescriptions = false)
        assertEquals("News", guide.programs.single().title)
        assertEquals(null, guide.programs.single().description)
        assertTrue(guide.warnings.none { it.contains("descriptions shortened") })
    }
}

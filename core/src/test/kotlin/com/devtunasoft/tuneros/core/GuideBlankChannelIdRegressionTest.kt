package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for a blank XMLTV channel ID. The fuzz test found a guide that named a channel "" (or spaces). A blank key
 * can match no programme in the playlist, so such a channel is now ignored, with its display name, and programmes that
 * point at it are dropped. Channels with real IDs are unchanged.
 */
class GuideBlankChannelIdRegressionTest {
    @Test fun aBlankChannelIdIsNeitherNamedNorUsedByProgrammes() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><tv>
<channel id=""><display-name>Blank</display-name></channel>
<channel id="   "><display-name>Spaces</display-name></channel>
<channel id="c1"><display-name>One</display-name></channel>
<programme start="20261122100000 +0000" stop="20261122110000 +0000" channel=""><title>Orphan</title></programme>
<programme start="20261122100000 +0000" stop="20261122110000 +0000" channel="c1"><title>Kept</title></programme>
</tv>"""
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals(setOf("c1"), guide.channelNames.keys)
        assertEquals(listOf("c1"), guide.programs.map { it.channelId })
    }
}

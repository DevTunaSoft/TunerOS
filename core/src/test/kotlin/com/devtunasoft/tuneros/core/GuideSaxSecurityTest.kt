package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import org.xml.sax.SAXException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The Android-compatible SAX path must never make DTD or entity support optional. */
class GuideSaxSecurityTest {
    private fun import(xml: String): GuideSnapshot =
        GuideParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test fun ordinaryGuideAndBuiltInXmlEscapesStillImport() {
        val guide = import("""<?xml version="1.0"?>
            <tv><channel id="news"><display-name>News &amp; Sports</display-name></channel>
              <programme channel="news" start="20261111100000 +0000"
                stop="20261111110000 +0000"><title>World &amp; Local</title></programme>
            </tv>""")
        assertEquals("News & Sports", guide.channelNames["news"])
        assertEquals("World & Local", guide.programs.single().title)
    }

    @Test fun externalDocumentTypeIsRejectedBeforeNetworkOrFileResolution() {
        assertFailsWith<SAXException> {
            import("""<!DOCTYPE tv SYSTEM "file:///etc/passwd">
                <tv><channel id="news"><display-name>News</display-name></channel></tv>""")
        }
    }

    @Test fun internalEntityExpansionIsRejectedBeforeRetainingProgrammes() {
        assertFailsWith<SAXException> {
            import("""<!DOCTYPE tv [<!ENTITY repeat "Expanded">]>
                <tv><programme channel="a" start="20261111100000 +0000"
                    stop="20261111110000 +0000"><title>&repeat;</title></programme></tv>""")
        }
    }

    @Test fun nestedEntityDefinitionsCannotConsumeUnboundedMemory() {
        assertFailsWith<SAXException> {
            import("""<!DOCTYPE tv [
                <!ENTITY a "aaaa">
                <!ENTITY b "&a;&a;&a;&a;">
                <!ENTITY c "&b;&b;&b;&b;">
                ]>
                <tv><programme channel="a" start="20261111100000 +0000"
                    stop="20261111110000 +0000"><title>&c;</title></programme></tv>""")
        }
    }
}

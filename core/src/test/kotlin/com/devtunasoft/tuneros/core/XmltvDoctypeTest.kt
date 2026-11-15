package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import org.xml.sax.SAXException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Only the canonical XMLTV DOCTYPE may reach the SAX parser. Every other declaration is refused first. */
class XmltvDoctypeTest {
    private val programme =
        """<programme channel="news" start="20261111100000 +0000" stop="20261111110000 +0000"><title>Evening</title></programme>"""

    private val canonical = """<!DOCTYPE tv SYSTEM "xmltv.dtd">"""

    private fun document(prolog: String): ByteArray =
        (prolog + """<tv><channel id="news"><display-name>News</display-name></channel>$programme</tv>""")
            .toByteArray(Charsets.UTF_8)

    private fun guideFrom(prolog: String): GuideSnapshot =
        GuideParser.parse(ByteArrayInputStream(document(prolog)))

    @Test fun canonicalXmltvDoctypeImportsStandardGuides() {
        val guide = guideFrom("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + canonical + "\n")
        assertEquals("News", guide.channelNames["news"])
        assertEquals("Evening", guide.programs.single().title)
    }

    @Test fun byteOrderMarkAndCommentsNeitherHideNorForgeTheDoctype() {
        val guide = guideFrom(
            "﻿<?xml version=\"1.0\"?>\n" +
                "<!-- <!DOCTYPE tv [ <!ENTITY a \"x\"> ]> is only comment text -->\n" +
                canonical + "\n",
        )
        assertEquals("Evening", guide.programs.single().title)
    }

    @Test fun foreignOrExternalDoctypesAreRefusedBeforeTheParserStarts() {
        listOf(
            """<!DOCTYPE tv SYSTEM "file:///etc/passwd">""",
            """<!DOCTYPE tv SYSTEM "http://example.invalid/xmltv.dtd">""",
            """<!DOCTYPE tv PUBLIC "-//XMLTV//DTD//EN" "xmltv.dtd">""",
            """<!DOCTYPE other SYSTEM "xmltv.dtd">""",
            """<!DOCTYPE tv SYSTEM 'xmltv.dtd'>""",
        ).forEach { doctype ->
            assertFailsWith<SAXException>(doctype) { guideFrom(doctype + "\n") }
        }
    }

    @Test fun internalSubsetsAreRefusedEvenWithTheCanonicalReference() {
        assertFailsWith<SAXException> {
            guideFrom("""<!DOCTYPE tv SYSTEM "xmltv.dtd" [<!ENTITY repeat "Expanded">]>""" + "\n")
        }
        assertFailsWith<SAXException> {
            guideFrom("""<!DOCTYPE tv [<!ENTITY repeat "Expanded">]>""" + "\n")
        }
    }

    @Test fun secondDoctypeIsRefused() {
        assertFailsWith<SAXException> { guideFrom("$canonical\n$canonical\n") }
    }

    @Test fun oversizedPrologIsRefusedBeforeItIsRead() {
        val padding = "<!--" + "x".repeat(XmltvProlog.MAX_BYTES) + "-->"
        assertFailsWith<SAXException> { guideFrom(padding + "\n") }
    }

    @Test fun utf16InputIsRefusedBecauseItsPrologCannotBeChecked() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x3C, 0x00, 0x74, 0x00, 0x76, 0x00)
        assertFailsWith<SAXException> { GuideParser.parse(ByteArrayInputStream(utf16)) }
    }

    @Test fun streamsWithoutMarkSupportGetTheSameCheck() {
        val payload = document(canonical + "\n")
        val guide = GuideParser.parse(object : FilterInputStream(ByteArrayInputStream(payload)) {
            override fun markSupported(): Boolean = false
        })
        assertEquals("Evening", guide.programs.single().title)
    }
}

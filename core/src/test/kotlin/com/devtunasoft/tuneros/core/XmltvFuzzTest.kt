package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import org.xml.sax.SAXException

/**
 * Deterministic fuzzing of the XMLTV parser. Random documents, built from mostly well-formed pieces plus broken, nested,
 * entity-bearing and oversized ones, may only be refused with SAXException, IOException or IllegalArgumentException. An
 * accepted guide must respect the text caps, keep its programmes in order, and never name a channel with a blank ID.
 * Every iteration is seeded, so a failure reproduces from the iteration number.
 */
class XmltvFuzzTest {
    private val fragments = listOf(
        "<channel id=\"c1\"><display-name>One</display-name></channel>",
        "<channel id=\"c2\"><display-name>Two &amp; Three</display-name></channel>",
        "<channel id=\"\"><display-name>Blank</display-name></channel>",
        "<programme start=\"20261122100000 +0000\" stop=\"20261122110000 +0000\" channel=\"c1\"><title>Show</title></programme>",
        "<programme start=\"20261122110000 +0000\" stop=\"20261122100000 +0000\" channel=\"c2\"><title>Reversed</title></programme>",
        "<programme start=\"bad\" stop=\"20261122110000\" channel=\"c1\"><title>Broken time</title></programme>",
        "<programme start=\"20261122100000 +0000\" stop=\"20261122120000 +0000\" channel=\"c2\"><title>${"t".repeat(600)}</title>" +
            "<desc>${"d".repeat(3_000)}</desc></programme>",
        "<programme start=\"20261122100000 +0000\" stop=\"20261122110000 +0000\"><title>No channel</title></programme>",
        "<programme channel=\"c1\"><title>No times</title></programme>",
        "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">", "<!DOCTYPE tv [<!ENTITY x SYSTEM \"http://e.example/x\">]>", "&xxe;", "&amp;",
        "<![CDATA[raw]]>", "<unknown>ignored</unknown>", "<title>loose</title>", "</channel>", "<", "\u0000", "é",
    )

    private fun parseOrNull(document: String): GuideSnapshot? =
        try {
            GuideParser.parse(ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)))
        } catch (refused: SAXException) {
            null
        } catch (refused: IOException) {
            null
        } catch (refused: IllegalArgumentException) {
            null
        }

    private fun checkGuide(guide: GuideSnapshot, iteration: Int) {
        assertTrue(guide.programs.size <= GuideParser.MAX_PROGRAMS, "iteration $iteration keeps too many programmes")
        assertTrue(
            guide.programs.all { it.channelId.isNotBlank() },
            "iteration $iteration names a programme with a blank channel",
        )
        assertTrue(
            guide.programs.all { it.title.length <= GuideParser.MAX_TITLE_CHARS },
            "iteration $iteration keeps a title over the cap",
        )
        assertTrue(
            guide.programs.all { it.endEpochSeconds > it.startEpochSeconds },
            "iteration $iteration keeps a programme that does not end after it starts",
        )
        assertTrue(
            guide.channelNames.keys.all { it.isNotBlank() },
            "iteration $iteration names a channel with a blank ID",
        )
    }

    @Test fun randomXmltvDocumentsAreRefusedOrAcceptedWithinTheCaps() {
        val random = Random(20261122)
        var accepted = 0
        repeat(1_500) { iteration ->
            val document = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
                repeat(random.nextInt(0, 12)) { append(fragments[random.nextInt(fragments.size)]) }
                if (random.nextInt(5) != 0) append("</tv>")
            }
            parseOrNull(document)?.let {
                checkGuide(it, iteration)
                accepted++
            }
        }
        // Refusing everything would pass vacuously, so enough random documents must parse to be checked.
        assertTrue(accepted >= 100, "only $accepted of 1500 random XMLTV documents parsed")
    }
}

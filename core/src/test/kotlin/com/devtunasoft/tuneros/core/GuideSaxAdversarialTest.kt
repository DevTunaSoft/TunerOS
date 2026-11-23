package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.lang.reflect.Modifier
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.xml.sax.SAXException

/**
 * Adversarial boundaries of the GuideParser SAX handler seam that the existing guide tests do not pin:
 * exact depth and text-cap edges across several characters() callbacks, exact window edges, timestamp
 * failure modes, warning aggregation, secret-free diagnostics and state isolation between parses.
 * All markers are invented non-URL strings; no provider address or credential appears in this file.
 */
class GuideSaxAdversarialTest {
    private val compact = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val withZone = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val base = LocalDateTime.of(2026, 12, 1, 12, 0)

    private fun compactAt(hours: Long, minutes: Long = 0): String =
        base.plusHours(hours).plusMinutes(minutes).format(compact)

    private fun zonedAt(hours: Long, minutes: Long = 0): String =
        OffsetDateTime.of(base, ZoneOffset.UTC).plusHours(hours).plusMinutes(minutes).format(withZone)

    private fun document(body: String): String = "<tv>$body</tv>"

    private fun parseBody(
        body: String,
        maxPrograms: Int = GuideParser.MAX_PROGRAMS,
        maxChannels: Int = GuideParser.MAX_CHANNELS,
        fallbackZone: ZoneId = ZoneOffset.UTC,
        window: GuideWindow? = null,
    ): GuideSnapshot = GuideParser.parse(
        ByteArrayInputStream(document(body).toByteArray(Charsets.UTF_8)),
        maxPrograms = maxPrograms,
        maxChannels = maxChannels,
        fallbackZone = fallbackZone,
        window = window,
    )

    private fun rawDocument(text: String): ByteArrayInputStream =
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    /** Alternating text and CDATA forces several characters() callbacks for one element. */
    private fun splitText(value: String, chunk: Int = 37): String = value.chunked(chunk)
        .mapIndexed { index, part -> if (index % 2 == 0) part else "<![CDATA[$part]]>" }
        .joinToString("")

    private fun programme(
        title: String,
        channel: String = "c",
        start: String = zonedAt(0),
        stop: String = zonedAt(1),
        desc: String? = null,
    ): String = buildString {
        append("<programme channel=\"").append(channel)
        append("\" start=\"").append(start)
        append("\" stop=\"").append(stop).append("\"><title>").append(title).append("</title>")
        if (desc != null) append("<desc>").append(desc).append("</desc>")
        append("</programme>")
    }

    @Test
    fun nestingExactlyAtTheDepthCapIsAcceptedAndOneMoreLevelIsRefused() {
        // tv is level 1, the programme is level 63, its title is level 64.
        val wrappers = GuideParser.MAX_XML_DEPTH - 3
        val accepted = parseBody(
            "<w>".repeat(wrappers) + programme("Edge") + "</w>".repeat(wrappers),
        )
        assertEquals("Edge", accepted.programs.single().title)
        assertTrue(accepted.warnings.isEmpty())

        val tooDeep = document(
            "<w>".repeat(wrappers + 1) + programme("Edge") + "</w>".repeat(wrappers + 1),
        )
        val refusal = assertFailsWith<SAXException> {
            GuideParser.parse(rawDocument(tooDeep))
        }
        assertTrue(refusal.message?.contains("nesting exceeds") == true)
    }

    @Test
    fun exactTextCapsHoldAcrossSeveralCharacterCallbacks() {
        val title = "T".repeat(GuideParser.MAX_TITLE_CHARS)
        val name = "N".repeat(GuideParser.MAX_CHANNEL_NAME_CHARS)
        val desc = "D".repeat(GuideParser.MAX_DESCRIPTION_CHARS)
        val guide = parseBody(
            "<channel id=\"c\"><display-name>${splitText(name)}</display-name></channel>" +
                programme(splitText(title), desc = splitText(desc)),
        )
        assertEquals(title, guide.programs.single().title)
        assertEquals(name, guide.channelNames.getValue("c"))
        assertEquals(desc, guide.programs.single().description)
        assertTrue(guide.warnings.isEmpty())
    }

    @Test
    fun overlongTextAcrossCallbacksIsCappedAndCountedPerElement() {
        val title = "T".repeat(GuideParser.MAX_TITLE_CHARS + 40)
        val name = "N".repeat(GuideParser.MAX_CHANNEL_NAME_CHARS + 40)
        val desc = "D".repeat(GuideParser.MAX_DESCRIPTION_CHARS + 40)
        val body = buildString {
            append("<channel id=\"c\"><display-name>${splitText(name)}</display-name></channel>")
            repeat(3) { append(programme(splitText(title), desc = splitText(desc))) }
        }
        val guide = parseBody(body)
        assertEquals(title.take(GuideParser.MAX_TITLE_CHARS), guide.programs[0].title)
        assertEquals(desc.take(GuideParser.MAX_DESCRIPTION_CHARS), guide.programs[2].description)
        assertEquals(name.take(GuideParser.MAX_CHANNEL_NAME_CHARS), guide.channelNames.getValue("c"))
        assertEquals(
            listOf(
                "3 programme titles shortened to ${GuideParser.MAX_TITLE_CHARS} characters",
                "1 channel names shortened to ${GuideParser.MAX_CHANNEL_NAME_CHARS} characters",
                "3 descriptions shortened to ${GuideParser.MAX_DESCRIPTION_CHARS} characters",
            ),
            guide.warnings,
        )
    }

    @Test
    fun windowRetainsExactStartAndExactEndButDropsTouchingNeighbours() {
        val from = base.toEpochSecond(ZoneOffset.UTC)
        val body = programme("Before", start = zonedAt(-3), stop = zonedAt(-2)) +
            programme("EndsAtFrom", start = zonedAt(-2), stop = zonedAt(0)) +
            programme("StartsAtFrom", start = zonedAt(0), stop = zonedAt(0, 30)) +
            programme("EndsAtTo", start = zonedAt(0, 30), stop = zonedAt(1)) +
            programme("StartsAtTo", start = zonedAt(1), stop = zonedAt(2)) +
            programme("After", start = zonedAt(2), stop = zonedAt(3))
        val guide = parseBody(body, window = GuideWindow(from, from + 3600))
        assertEquals(listOf("StartsAtFrom", "EndsAtTo"), guide.programs.map { it.title })
        assertTrue(guide.warnings.isEmpty())
    }

    @Test
    fun localTimestampUsesTheSuppliedFallbackZoneAndNamesItInTheWarning() {
        val zone = ZoneOffset.ofHours(5)
        val guide = parseBody(
            programme("Local", start = compactAt(0), stop = compactAt(1)),
            fallbackZone = zone,
        )
        assertEquals(
            base.toEpochSecond(zone),
            guide.programs.single().startEpochSeconds,
        )
        assertEquals(listOf("1 programmes lacked timezone: assumed +05:00"), guide.warnings)
    }

    @Test
    fun offsetTimestampsNeedNoFallbackZoneAndKeepTheirInstant() {
        val start = "20261201070000 +0530"
        val stop = "20261201080000 -0330"
        val guide = parseBody(programme("Offset", start = start, stop = stop))
        assertEquals(
            OffsetDateTime.parse(start, withZone).toEpochSecond(),
            guide.programs.single().startEpochSeconds,
        )
        assertEquals(
            OffsetDateTime.parse(stop, withZone).toEpochSecond(),
            guide.programs.single().endEpochSeconds,
        )
        assertTrue(guide.warnings.isEmpty())
    }

    @Test
    fun malformedBlankAndOverflowingTimestampsDropTheProgrammeWithoutEchoingText() {
        val cases = listOf(
            "MISSING" to false,
            "" to false,
            "   " to false,
            "20261201" to false,
            "2026120110000" to false,
            "20261201100000junk" to false,
            "20261201100000 +9999" to false,
            "99999999999999" to true,
            "20261301100000" to true,
        )
        cases.forEach { (raw, localForm) ->
            val attrs = if (raw == "MISSING") {
                "channel=\"c\" stop=\"${zonedAt(1)}\""
            } else {
                "channel=\"c\" start=\"$raw\" stop=\"${zonedAt(1)}\""
            }
            val guide = parseBody("<programme $attrs><title>Refused</title></programme>")
            assertTrue(guide.programs.isEmpty(), "timestamp case was retained")
            val expected = buildList {
                if (localForm) add("1 programmes lacked timezone: assumed UTC")
                add("1 programmes had invalid timestamps")
            }
            assertEquals(expected, guide.warnings, "timestamp case produced unexpected warnings")
        }
    }

    @Test
    fun invalidStopTimestampAloneAlsoFailsClosed() {
        val guide = parseBody(
            programme("Refused", start = zonedAt(0), stop = "bad"),
        )
        assertTrue(guide.programs.isEmpty())
        assertEquals(listOf("1 programmes had invalid timestamps"), guide.warnings)
    }

    @Test
    fun overlongChannelIdIsCappedBeforePoolingAndMissingIdCannotOwnAProgramme() {
        val id = "ch".repeat(200)
        val capped = id.take(256)
        val pooled = parseBody(
            "<channel id=\"$id\"><display-name>Long</display-name></channel>" +
                programme("First", channel = id) + programme("Second", channel = id),
        )
        assertEquals(setOf(capped), pooled.channelNames.keys)
        assertSame(pooled.channelNames.keys.single(), pooled.programs[0].channelId)
        assertSame(pooled.programs[0].channelId, pooled.programs[1].channelId)
        assertTrue(pooled.warnings.isEmpty())

        val missing = parseBody(
            "<channel><display-name>Unknown</display-name></channel>" +
                "<programme start=\"${zonedAt(0)}\" stop=\"${zonedAt(1)}\"><title>Orphan</title></programme>",
        )
        assertTrue(missing.channelNames.isEmpty())
        assertTrue(missing.programs.isEmpty())
        assertTrue(missing.warnings.isEmpty())
    }

    @Test
    fun allSevenWarningCategoriesAggregateWithoutGrowingPastSevenLines() {
        val longTitle = "T".repeat(GuideParser.MAX_TITLE_CHARS + 1)
        val longDesc = "D".repeat(GuideParser.MAX_DESCRIPTION_CHARS + 1)
        val longName = "N".repeat(GuideParser.MAX_CHANNEL_NAME_CHARS + 1)
        val body = buildString {
            append("<channel id=\"c1\"><display-name>${splitText(longName)}</display-name></channel>")
            append("<channel id=\"c2\"><display-name>Other</display-name></channel>")
            append(programme("Local", start = compactAt(0), stop = compactAt(1), desc = splitText(longDesc))
                .replace("<title>Local</title>", "<title>${splitText(longTitle)}</title>"))
            append(programme("Broken", start = "bad"))
            repeat(3) { append(programme("Short$it", start = zonedAt(it + 2L), stop = zonedAt(it + 3L))) }
        }
        val guide = parseBody(body, maxPrograms = 2, maxChannels = 1)
        assertEquals(2, guide.programs.size)
        assertEquals(
            listOf(
                "2 programmes omitted: limit 2",
                "1 channel names omitted: limit 1",
                "1 programmes lacked timezone: assumed UTC",
                "1 programmes had invalid timestamps",
                "1 programme titles shortened to ${GuideParser.MAX_TITLE_CHARS} characters",
                "1 channel names shortened to ${GuideParser.MAX_CHANNEL_NAME_CHARS} characters",
                "1 descriptions shortened to ${GuideParser.MAX_DESCRIPTION_CHARS} characters",
            ),
            guide.warnings,
        )
    }

    @Test
    fun manySkippedProgrammesProduceOneAggregatedWarningLine() {
        val body = (0 until 15).joinToString("") { index ->
            programme("P$index", start = zonedAt(index.toLong()), stop = zonedAt(index.toLong() + 1))
        }
        val guide = parseBody(body, maxPrograms = 3)
        assertEquals(3, guide.programs.size)
        assertEquals(listOf("12 programmes omitted: limit 3"), guide.warnings)
    }

    @Test
    fun warningsNeverEchoSyntheticMarkersFromAttributesOrPayload() {
        val idMarker = "SECRETID42"
        val textMarker = "SECRETTEXT42"
        val timeMarker = "SECRETTIME42"
        val id = idMarker + "x".repeat(GuideParser.MAX_TITLE_CHARS)
        val body =
            "<channel id=\"$id\"><display-name>${splitText("N".repeat(300) + textMarker)}</display-name></channel>" +
                programme(
                    splitText("T".repeat(300) + textMarker),
                    channel = id,
                    start = timeMarker,
                    desc = splitText("D".repeat(2100) + textMarker),
                )
        val warnings = parseBody(body).warnings
        assertTrue(warnings.isNotEmpty())
        listOf(idMarker, textMarker, timeMarker).forEach { marker ->
            assertTrue(warnings.none { it.contains(marker) })
        }
    }

    @Test
    fun truncatedDocumentFailsClosedWithoutEchoingPayloadTextInItsDiagnostic() {
        val marker = "SECRETPAYLOAD42"
        val text = document(
            "<channel id=\"c\"><display-name>$marker</display-name></channel>" +
                "<programme channel=\"c\" start=\"${zonedAt(0)}\" stop=\"${zonedAt(1)}\"><title>$marker</title>",
        )
        val refusal = assertFailsWith<SAXException> { GuideParser.parse(rawDocument(text)) }
        assertTrue(refusal.message?.contains(marker) != true)
    }

    @Test
    fun repeatedParseCyclesKeepNoRetainedStateAndNoMutableStaticFields() {
        repeat(60) { cycle ->
            val id = "cycle-$cycle"
            val channel = "<channel id=\"$id\"><display-name>Name</display-name></channel>"
            val clean = parseBody(channel + programme("Show", channel = id))
            assertEquals(1, clean.programs.size)
            assertTrue(clean.warnings.isEmpty())

            val capped = parseBody(
                (0 until 6).joinToString("") { index ->
                    programme("S$index", channel = id, start = zonedAt(index.toLong()), stop = zonedAt(index.toLong() + 1))
                },
                maxPrograms = 2,
            )
            assertEquals(2, capped.programs.size)
            assertEquals(listOf("4 programmes omitted: limit 2"), capped.warnings)

            assertFailsWith<SAXException> {
                parseBody(
                    "<programme channel=\"$id\" start=\"broken\" stop=\"${zonedAt(1)}\"><title>Truncated",
                )
            }
            assertEquals(clean, parseBody(channel + programme("Show", channel = id)))
        }
        val fields = GuideParser::class.java.declaredFields
        assertTrue(fields.none { !Modifier.isStatic(it.modifiers) && !it.isSynthetic })
        assertTrue(fields.none {
            Modifier.isStatic(it.modifiers) &&
                (MutableCollection::class.java.isAssignableFrom(it.type) ||
                    MutableMap::class.java.isAssignableFrom(it.type))
        })
    }
}

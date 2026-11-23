package com.devtunasoft.tuneros.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CORE-44: deterministic boundary coverage for XMLTV timestamp admission in [GuideParser].
 *
 * Contract: start/stop are ISO-8601-style 14-digit calendar timestamps (optionally followed by
 * an explicit numeric offset). ISO-8601 uses the proleptic year, so 0000 is a representable
 * calendar year and must not be silently dropped as an "invalid timestamp". The parser also owes
 * an honest diagnostic when a timestamp really is malformed.
 *
 * All inputs are synthetic scratch fixtures. No provider URL, credential, PIN or media payload
 * appears in any fixture, expectation or assertion text.
 */
class GuideXmltvTimestampBoundaryTest {
    private fun feed(start: String, stop: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="scratch-ch"><display-name>Scratch Channel</display-name></channel>
          <programme start="$start" stop="$stop" channel="scratch-ch">
            <title>Year Boundary</title>
            <desc>synthetic fixture</desc>
          </programme>
        </tv>
    """.trimIndent()

    private fun parse(start: String, stop: String, zone: ZoneOffset = ZoneOffset.UTC): GuideSnapshot =
        GuideParser.parse(
            feed(start, stop).byteInputStream(),
            fallbackZone = zone,
        )

    @Test fun yearZeroWithExplicitOffsetIsAdmittedAtProlepticYearZero() {
        val guide = parse("00000101000000 +0000", "00000101010000 +0000")
        val program = guide.programs.single()
        assertEquals("Year Boundary", program.title)
        assertEquals(
            LocalDateTime.of(0, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC),
            program.startEpochSeconds,
        )
        assertTrue(guide.warnings.none { it.contains("invalid timestamps") })
    }

    @Test fun yearZeroWithoutOffsetUsesTheFallbackZone() {
        val guide = parse("00000101000000", "00000101010000")
        val program = guide.programs.single()
        assertEquals(
            LocalDateTime.of(0, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC),
            program.startEpochSeconds,
        )
        assertTrue(guide.warnings.any { it.contains("programmes lacked timezone: assumed UTC") })
    }

    @Test fun ordinaryTimestampsKeepTheirExistingOffsetAndFallbackSemantics() {
        val withOffset = parse("20231114221328 +0000", "20231114231328 +0000")
        assertEquals(
            Instant.parse("2023-11-14T22:13:28Z").epochSecond,
            withOffset.programs.single().startEpochSeconds,
        )
        val plusTwo = ZoneOffset.ofHours(2)
        val noOffset = parse("20231114221328", "20231114231328", plusTwo)
        assertEquals(
            LocalDateTime.of(2023, 11, 14, 22, 13, 28).toEpochSecond(plusTwo),
            noOffset.programs.single().startEpochSeconds,
        )
        assertTrue(noOffset.warnings.any { it.contains("programmes lacked timezone: assumed +02:00") })
    }

    @Test fun firstCalendarYearStaysAcceptedAtTheBoundary() {
        val guide = parse("00010101000000 +0000", "00010101010000 +0000")
        assertEquals(
            LocalDateTime.of(1, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC),
            guide.programs.single().startEpochSeconds,
        )
    }

    @Test fun aGenuinelyMalformedTimestampIsCountedAndTheProgrammeIsDropped() {
        val guide = parse("not-a-timestamp", "20231114231328 +0000")
        assertTrue(guide.programs.isEmpty())
        assertTrue(guide.warnings.any { it.contains("1 programmes had invalid timestamps") })
    }
}

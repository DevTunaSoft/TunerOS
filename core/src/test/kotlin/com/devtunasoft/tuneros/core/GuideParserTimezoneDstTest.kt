package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic JVM regression coverage for CORE-01: XMLTV provider timezone offsets,
 * the explicit fallback zone, and java.time's documented DST overlap/gap resolution.
 *
 * These tests pin existing, correct behaviour; they do not invent a new DST warning
 * policy. Fixtures contain inert synthetic timestamps only: no provider URL, credential
 * or real feed.
 */
class GuideParserTimezoneDstTest {

    private fun xml(start: String, stop: String): String =
        """<tv><programme channel="c" start="$start" stop="$stop"><title>Show</title></programme></tv>"""

    private fun parse(start: String, stop: String, fallbackZone: ZoneId? = null): GuideSnapshot {
        val stream = ByteArrayInputStream(xml(start, stop).toByteArray(Charsets.UTF_8))
        return if (fallbackZone == null) {
            GuideParser.parse(stream)
        } else {
            GuideParser.parse(stream, fallbackZone = fallbackZone)
        }
    }

    /** Expected epoch seconds built independently of the parser's atZone/OffsetDateTime path. */
    private fun epoch(hour: Int, minute: Int, offset: ZoneOffset, day: Int = 8,
                      month: Int = 11): Long =
        OffsetDateTime.of(2026, month, day, hour, minute, 0, 0, offset).toEpochSecond()

    @Test fun fixedPositiveOffsetIsConvertedToUtcWithoutAnAssumedZoneWarning() {
        val plus0530 = ZoneOffset.ofHoursMinutes(5, 30)
        val report = parse("20261108100000 +0530", "20261108110000 +0530")
        val programme = report.programs.single()
        // 10:00 at +05:30 is 04:30 UTC.
        assertEquals(epoch(10, 0, plus0530), programme.startEpochSeconds)
        assertEquals(epoch(11, 0, plus0530), programme.endEpochSeconds)
        assertTrue(report.warnings.none { it.contains("lacked timezone") }, report.warnings.toString())
    }

    @Test fun fixedNegativeOffsetIsConvertedToUtc() {
        val minus0500 = ZoneOffset.ofHours(-5)
        val report = parse("20261108100000 -0500", "20261108110000 -0500")
        // 10:00 at -05:00 is 15:00 UTC.
        assertEquals(epoch(10, 0, minus0500), report.programs.single().startEpochSeconds)
    }

    @Test fun fixedNonUtcFallbackOffsetIsUsedAndNamedInTheWarning() {
        val plus0530 = ZoneOffset.ofHoursMinutes(5, 30)
        val report = parse("20261108100000", "20261108110000", fallbackZone = plus0530)
        assertEquals(epoch(10, 0, plus0530), report.programs.single().startEpochSeconds)
        assertTrue(report.warnings.any { it.contains("assumed +05:30") }, report.warnings.toString())
    }

    @Test fun ambiguousFallBackLocalTimeUsesTheEarlierOffsetAndStillWarns() {
        // 2026-11-01 01:30 occurs twice in America/New_York (EDT -04:00, then EST -05:00).
        // java.time resolves the overlap to the earlier offset deterministically; the
        // parser keeps that instant and still reports the missing-timezone assumption.
        val newYork = ZoneId.of("America/New_York")
        val report = parse("20261101013000", "20261101020000", fallbackZone = newYork)
        val programme = report.programs.single()
        assertEquals(epoch(1, 30, ZoneOffset.ofHours(-4), day = 1), programme.startEpochSeconds)
        assertTrue(programme.endEpochSeconds > programme.startEpochSeconds)
        assertTrue(report.warnings.any { it.contains("assumed America/New_York") }, report.warnings.toString())
    }

    @Test fun springForwardGapLocalTimeIsShiftedForwardByTheGap() {
        // 2026-03-08 02:30 does not exist in America/New_York (02:00 -> 03:00).
        // java.time shifts the gap time forward by the gap length to 03:30 EDT.
        val newYork = ZoneId.of("America/New_York")
        val report = parse("20260308023000", "20260308040000", fallbackZone = newYork)
        assertEquals(epoch(3, 30, ZoneOffset.ofHours(-4), day = 8, month = 3),
            report.programs.single().startEpochSeconds)
        assertTrue(report.warnings.any { it.contains("assumed America/New_York") }, report.warnings.toString())
    }

    @Test fun onlyOffsetLessProgrammesAreCountedInTheMissingTimezoneWarning() {
        // Mixed feed: the offset-bearing programme must not inflate the assumed-zone count.
        val mixed = """<tv>
<programme channel="c" start="20261108100000 +0000" stop="20261108110000 +0000"><title>Offset</title></programme>
<programme channel="c" start="20261108110000" stop="20261108120000"><title>Assumed</title></programme>
</tv>"""
        val report = GuideParser.parse(ByteArrayInputStream(mixed.toByteArray(Charsets.UTF_8)))
        assertEquals(2, report.programs.size)
        assertTrue(
            report.warnings.any { it.contains("1 programmes lacked timezone: assumed UTC") },
            report.warnings.toString()
        )
    }
}

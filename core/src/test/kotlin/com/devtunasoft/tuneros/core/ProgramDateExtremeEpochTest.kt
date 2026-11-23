package com.devtunasoft.tuneros.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CORE-33: adversarial coverage of the top-level EXT-X-PROGRAM-DATE-TIME formatter in
 * TimeshiftWindow.kt and the playlist tag that embeds it.
 *
 * CORE-08 covers window retention, rejected segments and a DST overlap at ordinary epochs;
 * CORE-19 covers the different GuideDoctor diagnostic formatter. Neither covers the year-zero,
 * negative-year or Long-boundary values that a malformed or replayed programme timestamp can
 * carry. The contract is ISO 8601 in UTC with milliseconds: a tag with a placeholder mask or a
 * wrong era is not parseable by a player.
 *
 * The oracle reads the same instant's fields directly and checks the emitted shape. No provider
 * address, credential, PIN or media payload appears in any input, expectation or assertion text.
 */
class ProgramDateExtremeEpochTest {
    private val shape = Regex(
        "^([+-]?\\d{4,})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})Z$",
    )

    private fun epochOf(year: Int): Long =
        LocalDateTime.of(year, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun assertIsoFields(epochMillis: Long, formatted: String) {
        val match = shape.matchEntire(formatted)
        assertNotNull(match, "not an ISO-8601 UTC instant with milliseconds: year input $epochMillis")
        assertFalse(formatted.contains('*'), "formatter placeholder in a programme date: $formatted")
        assertFalse(formatted.any { it.code < 32 || it.code == 127 }, "control character in a programme date")
        val instant = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        val parts = match.destructured
        assertEquals(instant.year, parts.component1().toInt(), "year for input $epochMillis")
        assertEquals(instant.monthValue, parts.component2().toInt(), "month for input $epochMillis")
        assertEquals(instant.dayOfMonth, parts.component3().toInt(), "day for input $epochMillis")
        assertEquals(instant.hour, parts.component4().toInt(), "hour for input $epochMillis")
        assertEquals(instant.minute, parts.component5().toInt(), "minute for input $epochMillis")
        assertEquals(instant.second, parts.component6().toInt(), "second for input $epochMillis")
        assertEquals(instant.nano / 1_000_000, parts.component7().toInt(), "millis for input $epochMillis")
    }

    @Test fun anOrdinaryEpochStillUsesTheExactExistingTagShape() {
        assertEquals("2023-11-14T22:13:28.000Z", programDate(1_700_000_008_000L))
        assertIsoFields(1_700_000_008_000L, programDate(1_700_000_008_000L))
    }

    @Test fun yearZeroIsNotReportedAsYearOne() {
        val epoch = epochOf(0)
        assertEquals("0000-01-01T00:00:00.000Z", programDate(epoch))
        assertIsoFields(epoch, programDate(epoch))
    }

    @Test fun aNegativeExtremeEpochKeepsItsSignAndExactFields() {
        assertIsoFields(Long.MIN_VALUE, programDate(Long.MIN_VALUE))
        assertIsoFields(epochOf(-1), programDate(epochOf(-1)))
    }

    @Test fun aPositiveExtremeEpochUsesDigitsRatherThanAFormatterPlaceholder() {
        val formatted = programDate(Long.MAX_VALUE)
        assertIsoFields(Long.MAX_VALUE, formatted)
        assertTrue(formatted.startsWith('+'), "an ISO year past 9999 carries an explicit plus sign")
    }

    @Test fun boundaryYearsAroundFourDigitsStayParseable() {
        for (year in listOf(9999, 10_000, 10_001)) {
            val epoch = epochOf(year)
            assertIsoFields(epoch, programDate(epoch))
        }
    }

    @Test fun aWindowPlaylistEmitsValidProgramDateTagsAtBothLongBounds() {
        val window = TimeshiftWindow(maxSegments = 4, maxBytes = 10_000)
        window.add(TimeshiftWindow.Segment(0, 2.0, 1, "seg-0.ts", programDateMillis = Long.MIN_VALUE))
        window.add(TimeshiftWindow.Segment(1, 2.0, 1, "seg-1.ts", programDateMillis = 1_700_000_008_000L))
        window.add(TimeshiftWindow.Segment(2, 2.0, 1, "seg-2.ts", programDateMillis = Long.MAX_VALUE))
        val tags = window.playlist().lines().filter { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }
        assertEquals(3, tags.size)
        val epochs = listOf(Long.MIN_VALUE, 1_700_000_008_000L, Long.MAX_VALUE)
        tags.zip(epochs).forEach { (tag, epoch) ->
            val value = tag.removePrefix("#EXT-X-PROGRAM-DATE-TIME:")
            assertIsoFields(epoch, value)
        }
    }

    @Test fun interleavingExtremeAndOrdinaryEpochsKeepsTheFormatterStateless() {
        val epochs = listOf(
            Long.MIN_VALUE,
            epochOf(-1),
            epochOf(0),
            0L,
            1_700_000_008_000L,
            epochOf(9999),
            epochOf(10_000),
            Long.MAX_VALUE,
        )
        val first = epochs.map { programDate(it) }
        repeat(512) { pass ->
            for (index in epochs.indices) {
                assertEquals(first[index], programDate(epochs[index]), "pass $pass index $index")
            }
        }
        for (index in epochs.indices) {
            assertIsoFields(epochs[index], first[index])
        }
    }
}

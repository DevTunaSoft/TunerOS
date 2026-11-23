package com.devtunasoft.tuneros.core

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic coverage of the series planner on DST boundaries and at the rule budget. Instants are built from
 * explicit zones and offsets, so these checks do not depend on the host's default timezone or current date.
 */
class SeriesPlanDstBoundaryTest {
    private val zone = ZoneId.of("America/Chicago")

    private fun localEpoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toEpochSecond()

    private fun offsetEpoch(hour: Int, offsetHours: Int): Long =
        OffsetDateTime.of(2026, 11, 1, hour, 30, 0, 0, ZoneOffset.ofHours(offsetHours)).toEpochSecond()

    private fun program(channel: String, title: String, start: Long, end: Long = start + 1_800) =
        GuideProgram(channelId = channel, title = title, startEpochSeconds = start, endEpochSeconds = end)

    private fun ruleOf(title: String) =
        SeriesRules.ruleFor(title, null) ?: error("no rule for $title")

    /** The skipped local hour must not merge the episodes on either side of the spring-forward jump. */
    @Test fun springForwardPlansBothSidesOfTheGapAndDropsARepeatedEntry() {
        val before = localEpoch(2026, 3, 8, 1, 30)
        val after = localEpoch(2026, 3, 8, 3, 30)
        assertEquals(3_600L, after - before)
        val earlier = program("qa-ch", "The News", before)
        val later = program("qa-ch", "The News", after)
        assertEquals(
            listOf(earlier, later),
            SeriesRules.plan(
                listOf(ruleOf("The News")),
                listOf(earlier.copy(), later, earlier),
                nowEpochSeconds = before - 60,
            ),
        )
    }

    /** The repeated local hour at fall-back has two distinct instants; both offset instances must be planned. */
    @Test fun fallBackPlansBothOffsetInstancesOfTheRepeatedHour() {
        val daylight = offsetEpoch(1, -5)
        val standard = offsetEpoch(1, -6)
        assertEquals(3_600L, standard - daylight)
        val first = program("qa-ch", "The News", daylight)
        val second = program("qa-ch", "The News", standard)
        assertEquals(
            listOf(second, first),
            SeriesRules.plan(
                listOf(ruleOf("The News")),
                listOf(second, first.copy(), first),
                nowEpochSeconds = daylight - 60,
            ),
        )
    }

    /** A programme starting exactly at "now" is not future, including at a DST transition. */
    @Test fun aProgrammeStartingAtNowIsNotPlannedAtTheFallBackInstant() {
        val start = offsetEpoch(1, -6)
        val startingNow = program("qa-ch", "The News", start)
        val upcoming = program("qa-ch", "The News", start + 1)
        assertEquals(
            listOf(upcoming),
            SeriesRules.plan(listOf(ruleOf("The News")), listOf(startingNow, upcoming), nowEpochSeconds = start),
        )
    }

    /**
     * Distinct episodes can share a start instant when a guide is corrected around a transition. Planning must keep
     * each different programme once, not collapse everything on a channel that starts at the same instant.
     */
    @Test fun distinctProgrammesWithTheSameStartInstantAreEachPlannedOnce() {
        val start = offsetEpoch(1, -6)
        val regular = program("qa-ch", "The News", start, start + 1_800)
        val special = program("qa-ch", "The News Special", start, start + 3_600)
        assertEquals(
            listOf(regular, special),
            SeriesRules.plan(
                listOf(ruleOf("The News"), ruleOf("The News Special")),
                listOf(regular, special, regular.copy()),
                nowEpochSeconds = start - 60,
            ),
        )
    }

    /** A full rule budget over a guide spanning the transition must match the naive oracle exactly. */
    @Test fun aFullBoundedRuleSetMatchesTheOracleAcrossTheTransition() {
        val rules = List(SeriesRules.MAX_RULES) { ruleOf("Series $it") }
        val boundary = offsetEpoch(1, -5)
        val programs = buildList {
            repeat(200) { index ->
                val start = boundary - 100 * 3_600L + index * 1_800L
                val title = "Series ${index % SeriesRules.MAX_RULES}"
                val channel = "ch-${index % 7}"
                add(program(channel, title, start))
                if (index % 25 == 0) add(program(channel, title, start))
                add(program(channel, "Unmatched $index", start + 1))
            }
        }
        val now = boundary - 101 * 3_600L
        val expected = programs
            .filter { it.startEpochSeconds > now && rules.any { rule -> SeriesRules.matches(rule, it) } }
            .distinct()
        assertEquals(expected, SeriesRules.plan(rules, programs, now))
        assertEquals(expected.size, expected.distinct().size)
        assertTrue(expected.size >= 200, "only ${expected.size} programmes planned")
    }
}

package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property test of the series planner against a naive oracle. The oracle keeps, in guide order and without repeats, the
 * programmes that have not started and that some rule matches. Random guides with repeats and near-identical titles, and
 * random rules with and without a channel, must give exactly that list. Every iteration is seeded, so a failure
 * reproduces from the iteration number.
 */
class SeriesPlanPropertyTest {
    private val titles = listOf("News at Ten", "news  at ten!", "The Archers", "Other show", "")
    private val channels = listOf("c1", "c2", "c3")

    @Test fun plansMatchTheNaiveOracleOnRandomGuidesAndRules() {
        val random = Random(20261122)
        val now = 1_000_000L
        var nonEmpty = 0
        repeat(1_000) { iteration ->
            val programs = List(random.nextInt(0, 30)) {
                val start = now + random.nextLong(-5_000L, 10_000L)
                GuideProgram(
                    channelId = channels[random.nextInt(channels.size)],
                    title = titles[random.nextInt(titles.size)],
                    startEpochSeconds = start,
                    endEpochSeconds = start + random.nextLong(1L, 4_000L),
                )
            }
            val rules = (0 until random.nextInt(0, 4)).mapNotNull {
                SeriesRules.ruleFor(
                    titles[random.nextInt(titles.size)],
                    if (random.nextBoolean()) null else channels[random.nextInt(channels.size)],
                )
            }
            val expected = programs
                .filter { program -> program.startEpochSeconds > now && rules.any { SeriesRules.matches(it, program) } }
                .distinct()
            val planned = SeriesRules.plan(rules, programs, now)
            assertEquals(expected, planned, "iteration $iteration")
            if (planned.isNotEmpty()) nonEmpty++
        }
        // Empty plans would agree trivially, so enough iterations must plan something to check the matching.
        assertTrue(nonEmpty >= 200, "only $nonEmpty of 1000 iterations planned anything")
    }
}

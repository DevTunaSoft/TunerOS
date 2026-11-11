package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Reproducible index correctness checks with overlapping and out-of-order XMLTV. */
class GuideIndexRegressionTest {
    private val ordering =
        compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds }

    @Test fun indexedWindowsMatchLinearReferenceForOverlappingAndDisorderedShows() {
        val random = Random(20261111)
        repeat(35) {
            val input = List(200) { index ->
                val start = random.nextLong(-10_000, 20_000)
                val duration = random.nextLong(1, 20_000)
                GuideProgram("channel", "P$index", start, start + duration)
            }.shuffled(random)
            val guide = GuideSnapshot(input, emptyMap())
            guide.prepareIndex()
            val sorted = input.sortedWith(ordering)
            repeat(30) {
                val from = random.nextLong(-11_000, 22_000)
                val to = from + random.nextLong(1, 3_600)
                val expected = sorted.filter {
                    it.startEpochSeconds < to && it.endEpochSeconds > from
                }
                assertEquals(expected, guide.programsBetween("channel", from, to))
                val expectedNow = sorted.lastOrNull {
                    it.startEpochSeconds <= from && it.endEpochSeconds > from
                }
                assertEquals(expectedNow, guide.now("channel", from))
            }
            // The user-provided original list must not be sorted/mutated by indexing.
            assertEquals(input, guide.programs)
        }
    }

    @Test fun longOverlapAndFutureOnlyChannelsKeepCorrectScheduleSemantics() {
        val live = GuideProgram("live", "Long running", -2_000, 15_000)
        val expired = (0 until 1_000).map {
            GuideProgram("live", "Expired$it", it.toLong(), it.toLong() + 1)
        }
        val future = GuideProgram("future", "Tomorrow", 50_000, 50_500)
        val guide = GuideSnapshot(expired + future + live, emptyMap())
        guide.prepareIndex()
        assertEquals(listOf(live), guide.programsBetween("live", 10_000, 10_100))
        assertEquals(live, guide.now("live", 10_000))
        assertTrue(guide.hasSchedule("future"))
        assertTrue(guide.programsBetween("future", 0, 1_000).isEmpty())
        assertEquals(null, guide.currentProgramsAt(10_000)["future"])
        assertEquals(live, guide.currentProgramsAt(10_000)["live"])
        assertFailsWith<IllegalArgumentException> { guide.programsBetween("live", 1_000, 1_000) }
    }
}

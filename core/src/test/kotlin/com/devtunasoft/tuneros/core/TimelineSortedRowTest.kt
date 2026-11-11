package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fast renderer path must preserve gap clipping and XMLTV-overlap semantics. */
class TimelineSortedRowTest {
    private val comparator =
        compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds }

    @Test fun indexedGuideRowsMatchGeneralTimelineAcrossDisorderedAndOverlappingData() {
        val random = Random(1865)
        repeat(30) {
            val programs = List(150) { n ->
                val start = random.nextLong(-5_000L, 12_000L)
                val duration = random.nextLong(1L, 4_000L)
                GuideProgram("c", "P$n", start, start + duration)
            }.shuffled(random)
            val snapshot = GuideSnapshot(programs, emptyMap())
            snapshot.prepareIndex()

            repeat(35) {
                val from = random.nextLong(-5_000L, 15_000L)
                val to = from + random.nextLong(1L, 3_600L)
                val expected = TimelineLayout.row(programs, from, to)
                val fromIndex = snapshot.programsBetween("c", from, to)
                assertEquals(fromIndex.sortedWith(comparator), fromIndex)
                val actual = TimelineLayout.rowFromSorted(fromIndex, from, to)
                assertEquals(expected, actual)
                assertTrue(actual.zipWithNext().all { (a, b) ->
                    a.endEpochSeconds == b.startEpochSeconds
                })
            }
        }
    }

    @Test fun emptyOrExpiredWindowRendersOnlyOneGap() {
        val old = List(1_000) { n ->
            GuideProgram("c", "Old$n", n.toLong(), n.toLong() + 1L)
        }
        val expected = listOf(TimelineEntry(2_000L, 3_000L))
        assertEquals(expected, TimelineLayout.rowFromSorted(emptyList(), 2_000, 3_000))
        assertEquals(expected, TimelineLayout.rowFromSorted(old, 2_000, 3_000))
    }

    @Test fun shortLaterProgrammeAfterLongOverlapStillAppearsWhenVisible() {
        val first = GuideProgram("c", "Long", 0, 100)
        val hidden = GuideProgram("c", "Hidden", 30, 40)
        val later = GuideProgram("c", "Later", 90, 130)
        val actual = TimelineLayout.rowFromSorted(listOf(first, hidden, later), 10, 120)
        assertEquals(listOf("Long", "Later"), actual.mapNotNull { it.program?.title })
        assertEquals(10, actual.first().startEpochSeconds)
        assertEquals(120, actual.last().endEpochSeconds)
    }
}

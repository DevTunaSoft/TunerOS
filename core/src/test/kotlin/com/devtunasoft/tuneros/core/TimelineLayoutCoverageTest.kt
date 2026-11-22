package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage of a timeline row. Whatever the programmes, the row must tile its window exactly: entries are contiguous from
 * the window start to its end, a programme cell lies inside its programme, uncovered time is a gap, and two gaps never sit
 * side by side. The random programmes include overlaps, programmes that start before or end after the window, and zero-length
 * and reversed intervals, which GuideProgram does not reject.
 */
class TimelineLayoutCoverageTest {
    @Test fun aRowTilesItsWindowForRandomProgrammes() {
        for (seed in 0 until 500) {
            val random = Random(seed)
            val from = random.nextLong(0L, 100_000L)
            val to = from + random.nextLong(1L, 20_000L)
            val programs = List(random.nextInt(0, 40)) { index ->
                val start = from + random.nextLong(-5_000L, 25_000L)
                GuideProgram("c", "p$index", start, start + random.nextLong(-100L, 6_000L))
            }
            val row = TimelineLayout.row(programs, from, to)
            assertEquals(from, row.first().startEpochSeconds, "seed $seed: first entry")
            assertEquals(to, row.last().endEpochSeconds, "seed $seed: last entry")
            for (index in 1 until row.size) {
                assertEquals(row[index - 1].endEpochSeconds, row[index].startEpochSeconds, "seed $seed: entry $index")
            }
            assertTrue(row.zipWithNext().none { (a, b) -> a.isGap && b.isGap }, "seed $seed: adjacent gaps")
            for (entry in row) {
                val program = entry.program ?: continue
                assertTrue(
                    entry.startEpochSeconds >= program.startEpochSeconds &&
                        entry.endEpochSeconds <= program.endEpochSeconds,
                    "seed $seed: cell outside its programme",
                )
            }
        }
    }
}

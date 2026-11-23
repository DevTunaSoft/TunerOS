package com.devtunasoft.tuneros.guide

import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.TimelineFocusPolicy
import com.devtunasoft.tuneros.core.TimelineLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-01 objective coverage of the window-clipping and focus policies the cable guide consumes.
 * A resize/window shift, a large font (dense visible cells) and an overscan-trimmed edge all reduce
 * to the same pure question: after clipping, does the focused programme still have a stable, visible
 * cell? Every value below is a deterministic integer fixture; nothing is read from a screen.
 */
class GuideRowWindowFocusTest {
    private fun rowFor(vararg programs: GuideProgram, from: Long, to: Long) =
        TimelineLayout.rowFromSorted(programs.toList(), from, to)

    @Test
    fun aProgrammeClippedAtTheWindowEdgeKeepsItsFocusAnchor() {
        val early = GuideProgram("c", "Early", 0, 100)
        val focus = GuideProgram("c", "Focus", 100, 200)
        val late = GuideProgram("c", "Late", 200, 300)
        val snapshot = GuideSnapshot(listOf(early, focus, late), emptyMap())
        snapshot.prepareIndex()

        // The window shifted right until only half of "Focus" remains visible.
        val from = 150L
        val to = 250L
        val row = TimelineLayout.rowFromSorted(snapshot.programsBetween("c", from, to), from, to)

        assertEquals(2, row.size)
        assertEquals(from, row.first().startEpochSeconds) // cell is clipped to the window
        assertEquals(100L, row.first().program?.startEpochSeconds!!) // anchor is the original start
        val index = TimelineFocusPolicy.preferredIndex(row, 100L)
        assertEquals(0, index)
        assertEquals("Focus", row[index].program?.title)
    }

    @Test
    fun aDenseVisibleWindowAlwaysHasAStableFocusTargetForEachProgramme() {
        val step = 450L
        val programs = (0 until 24).map { i ->
            GuideProgram("dense", "P$i", i * step, (i + 1) * step)
        }
        val from = 0L
        val to = 24 * step
        val row = TimelineLayout.rowFromSorted(programs, from, to)
        assertEquals(24, row.size)
        for (program in programs) {
            val index = TimelineFocusPolicy.preferredIndex(row, program.startEpochSeconds)
            assertTrue("no focus target for ${program.title}", index >= 0)
            assertEquals(program.title, row[index].program?.title)
        }
    }

    @Test
    fun aShiftedFocusNeverLandsOnAGap() {
        val programs = listOf(
            GuideProgram("c", "One", 0, 10),
            GuideProgram("c", "Two", 20, 30),
            GuideProgram("c", "Three", 40, 50),
        )
        val row = rowFor(*programs.toTypedArray(), from = 0, to = 60)
        for (anchor in listOf(null, 0L, 5L, 20L, 35L, 50L, 999L)) {
            for (direction in listOf(-1, 1)) {
                val index = TimelineFocusPolicy.shiftedIndex(row, anchor, direction)
                if (index >= 0) {
                    assertNotNull("shifted focus landed on a gap: anchor=$anchor dir=$direction", row[index].program)
                }
            }
        }
    }

    @Test
    fun clippedWindowEdgesTileExactlyWithoutOverlap() {
        val programs = listOf(
            GuideProgram("c", "Before", 0, 50),
            GuideProgram("c", "Inside", 50, 150),
            GuideProgram("c", "After", 150, 300),
        )
        for ((from, to) in listOf(40L to 60L, 60L to 140L, 140L to 200L, 10L to 400L)) {
            val row = TimelineLayout.rowFromSorted(programs, from, to)
            assertEquals("first entry for [$from,$to)", from, row.first().startEpochSeconds)
            assertEquals("last entry for [$from,$to)", to, row.last().endEpochSeconds)
            for (i in 1 until row.size) {
                assertEquals("gap/overlap at $i for [$from,$to)", row[i - 1].endEpochSeconds, row[i].startEpochSeconds)
            }
            for (entry in row) {
                val program = entry.program ?: continue
                assertTrue("clipped cell outside its programme", entry.startEpochSeconds >= program.startEpochSeconds)
                assertTrue("clipped cell outside its programme", entry.endEpochSeconds <= program.endEpochSeconds)
            }
        }
    }
}

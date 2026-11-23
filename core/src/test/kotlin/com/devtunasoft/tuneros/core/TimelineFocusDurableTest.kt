package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Deterministic focus invariants for moving guide windows; all fixtures are synthetic. */
class TimelineFocusDurableTest {

    private fun entry(start: Long, end: Long, title: String? = null) =
        TimelineEntry(start, end, title?.let { GuideProgram("synthetic", it, start, end) })

    @Test
    fun focusInvariantsHoldAcrossMovingWindows() {
        // One long schedule; slide a 600s window across it in 60s steps.
        val schedule = (0 until 40).map { i ->
            val start = i * 120L
            GuideProgram("synthetic", "P$i", start, start + 120L)
        }
        for (step in 0..14) {
            val from = step * 60L
            val to = from + 600L
            val row = TimelineLayout.row(schedule, from, to)
            val selectable = row.mapIndexedNotNull { i, e -> e.program?.let { i to it.startEpochSeconds } }
            val label = "window $from..$to"

            if (selectable.isEmpty()) {
                assertEquals(-1, TimelineFocusPolicy.preferredIndex(row, null), label)
                assertEquals(-1, TimelineFocusPolicy.preferredIndex(row, from), label)
                assertEquals(-1, TimelineFocusPolicy.shiftedIndex(row, from, -1), label)
                assertEquals(-1, TimelineFocusPolicy.shiftedIndex(row, from, 1), label)
                continue
            }

            // Preferred: an exact start wins, a missing start falls back to the leftmost programme.
            assertEquals(selectable.first().first, TimelineFocusPolicy.preferredIndex(row, null), label)
            assertEquals(selectable.first().first, TimelineFocusPolicy.preferredIndex(row, -999L), label)
            for ((index, start) in selectable) {
                assertEquals(index, TimelineFocusPolicy.preferredIndex(row, start), "$label: start $start")
            }

            // nextIndex lands only on programmes and stops at both boundaries.
            for ((index, _) in selectable) {
                val right = TimelineFocusPolicy.nextIndex(row, index, 1)
                val left = TimelineFocusPolicy.nextIndex(row, index, -1)
                assertTrue(right == -1 || row[right].program != null, "$label: right landed on a gap")
                assertTrue(left == -1 || row[left].program != null, "$label: left landed on a gap")
                if (right >= 0) assertTrue(right > index, "$label: right must advance")
                if (left >= 0) assertTrue(left < index, "$label: left must retreat")
            }
            assertEquals(-1, TimelineFocusPolicy.nextIndex(row, selectable.last().first, 1), label)
            assertEquals(-1, TimelineFocusPolicy.nextIndex(row, selectable.first().first, -1), label)

            // shiftedIndex selects the nearest programme in the travel direction, never a gap.
            val anchors = listOf(null, -999L, from, from + 300L, to, to + 999L) + selectable.map { it.second }
            for (anchor in anchors) {
                for (direction in listOf(-1, 1)) {
                    val index = TimelineFocusPolicy.shiftedIndex(row, anchor, direction)
                    assertTrue(index == -1 || row[index].program != null, "$label: shifted selected a gap")
                    if (anchor == null) continue
                    val eligible = selectable.filter { if (direction < 0) it.second < anchor else it.second > anchor }
                    if (eligible.isEmpty()) continue
                    val expected = if (direction < 0) eligible.maxByOrNull { it.second }!!.first
                    else eligible.minByOrNull { it.second }!!.first
                    assertEquals(expected, index, "$label: shifted anchor=$anchor direction=$direction")
                }
            }
        }
    }

    @Test
    fun missingPreferredProgrammeFallsBackToFirstVisibleProgramme() {
        val row = listOf(
            entry(0L, 10L),
            entry(10L, 20L, "First"),
            entry(20L, 30L, "Second"),
        )
        assertEquals(1, TimelineFocusPolicy.preferredIndex(row, 999L))
        assertEquals(1, TimelineFocusPolicy.preferredIndex(row, 5L))
        assertEquals(1, TimelineFocusPolicy.preferredIndex(row, null))
        assertEquals(2, TimelineFocusPolicy.preferredIndex(row, 20L))
    }

    @Test
    fun allGapRowsHaveNoSelectableFocus() {
        val row = listOf(entry(0L, 10L), entry(10L, 20L), entry(20L, 30L))
        assertEquals(-1, TimelineFocusPolicy.preferredIndex(row, null))
        assertEquals(-1, TimelineFocusPolicy.preferredIndex(row, 10L))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(row, 0, 1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(row, 2, -1))
        assertEquals(-1, TimelineFocusPolicy.shiftedIndex(row, null, 1))
        assertEquals(-1, TimelineFocusPolicy.shiftedIndex(row, 15L, -1))
        assertEquals(-1, TimelineFocusPolicy.shiftedIndex(emptyList(), null, 1))
    }

    @Test
    fun nextIndexAndShiftedIndexRespectDirectionBoundaries() {
        val row = listOf(
            entry(0L, 10L),
            entry(10L, 20L, "A"),
            entry(20L, 30L),
            entry(30L, 40L, "B"),
        )
        assertEquals(1, TimelineFocusPolicy.nextIndex(row, -1, 1))
        assertEquals(1, TimelineFocusPolicy.nextIndex(row, 0, 1))
        assertEquals(3, TimelineFocusPolicy.nextIndex(row, 1, 1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(row, 3, 1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(row, 1, -1))
        assertEquals(3, TimelineFocusPolicy.nextIndex(row, 4, -1))

        assertEquals(3, TimelineFocusPolicy.shiftedIndex(row, 45L, -1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(row, 40L, -1))
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(row, 10L, -1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(row, 30L, 1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(row, 50L, 1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(row, 50L, -1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(row, null, -1))
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(row, null, 1))

        assertFailsWith<IllegalArgumentException> { TimelineFocusPolicy.nextIndex(row, 1, 0) }
        assertFailsWith<IllegalArgumentException> { TimelineFocusPolicy.shiftedIndex(row, null, 2) }
    }
}

package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deterministic, synthetic large-window coverage for [TimelineLayout] and widthUnits.
 * Every fixture is built from integer arithmetic: no randomness and no provider data.
 */
class TimelineLayoutDurableTest {

    private fun guide(start: Long, end: Long, title: String) =
        GuideProgram("synthetic", title, start, end)

    private fun assertTilesWindow(row: List<TimelineEntry>, from: Long, to: Long, label: String) {
        assertTrue(row.isNotEmpty(), "$label: empty row")
        assertEquals(from, row.first().startEpochSeconds, "$label: first start")
        assertEquals(to, row.last().endEpochSeconds, "$label: last end")
        for (index in row.indices) {
            val entry = row[index]
            assertTrue(entry.endEpochSeconds > entry.startEpochSeconds, "$label: empty cell $index")
            if (index > 0) {
                assertEquals(
                    row[index - 1].endEpochSeconds,
                    entry.startEpochSeconds,
                    "$label: gap or overlap at cell $index",
                )
            }
        }
        assertEquals(to - from, row.sumOf { it.durationSeconds }, "$label: window coverage sum")
    }

    private fun assertFocusNeverSelectsGap(row: List<TimelineEntry>, label: String) {
        for (preferred in listOf(null, -1L, 0L, 150L, 3010L, Long.MAX_VALUE)) {
            val index = TimelineFocusPolicy.preferredIndex(row, preferred)
            assertTrue(index == -1 || row[index].program != null, "$label: preferred $preferred selected a gap")
        }
        for (direction in listOf(-1, 1)) {
            for (start in row.indices) {
                val index = TimelineFocusPolicy.nextIndex(row, start, direction)
                assertTrue(index == -1 || row[index].program != null, "$label: next($start,$direction) selected a gap")
            }
            for (anchor in listOf(null, -1L, 0L, 150L, 3600L, Long.MAX_VALUE)) {
                val index = TimelineFocusPolicy.shiftedIndex(row, anchor, direction)
                assertTrue(index == -1 || row[index].program != null, "$label: shifted($anchor,$direction) selected a gap")
            }
        }
    }

    @Test
    fun thousandsOfOverlappingProgrammesProduceBoundedTiledRows() {
        val from = 0L
        val to = 3600L
        // 12 visible programmes of 200s on a 300s cadence: 11 internal 100s gaps plus a trailing gap.
        val base = (0 until 12).map { i -> guide(i * 300L, i * 300L + 200L, "Base$i") }
        // 5_000 heavily overlapping programmes, all fully inside Base0 and clipped away.
        val noise = (0 until 5_000).map { i -> guide(10L, 11L + (i % 80).toLong(), "Noise$i") }
        val programmes = noise + base.asReversed()

        val row = TimelineLayout.row(programmes, from, to)
        assertTilesWindow(row, from, to, "overlap")
        assertEquals(24, row.size, "row must be bounded by visible cells, not by 5_012 inputs")
        assertEquals(12, row.count { it.isGap })

        val expectedTitles: List<String?> = (0 until 12).flatMap { i -> listOf("Base$i", null) }
        assertEquals(expectedTitles, row.map { it.program?.title })
        assertFocusNeverSelectsGap(row, "overlap")

        val sorted = programmes.sortedWith(compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds })
        assertEquals(row, TimelineLayout.rowFromSorted(sorted, from, to))
    }

    @Test
    fun clippedAndGapProgrammesTileEdgeWindowsExactly() {
        val from = 100L
        val to = 500L
        val before = guide(-1_000L, 200L, "Before")
        val inside = guide(250L, 300L, "Inside")
        val after = guide(400L, 900L, "After")
        // 2_000 programmes ending before the window and 2_000 overlapping ones that get clipped.
        val expired = (0 until 2_000).map { i -> guide(i.toLong() - 5_000L, i.toLong() - 4_999L, "Expired$i") }
        val noise = (0 until 2_000).map { i -> guide(150L, 151L + (i % 20).toLong(), "Noise$i") }

        val row = TimelineLayout.row(noise + expired + listOf(before, inside, after), from, to)
        assertTilesWindow(row, from, to, "clipped")
        assertEquals(listOf("Before", null, "Inside", null, "After"), row.map { it.program?.title })
        assertEquals(listOf(100L, 200L, 250L, 300L, 400L), row.map { it.startEpochSeconds })
        assertEquals(listOf(200L, 250L, 300L, 400L, 500L), row.map { it.endEpochSeconds })
        assertFocusNeverSelectsGap(row, "clipped")

        // The fast path must produce the identical clipped row for sorted input.
        val sorted = (noise + expired + listOf(before, inside, after))
            .sortedWith(compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds })
        assertEquals(row, TimelineLayout.rowFromSorted(sorted, from, to))
    }

    @Test
    fun widthUnitsHandlesNormalAndBoundaryDurations() {
        assertEquals(240, TimelineLayout.widthUnits(TimelineEntry(0L, 3600L)))
        assertEquals(120, TimelineLayout.widthUnits(TimelineEntry(0L, 1800L)))
        assertEquals(1, TimelineLayout.widthUnits(TimelineEntry(0L, 1L)))
        assertEquals(1, TimelineLayout.widthUnits(TimelineEntry(0L, 14L)))
        assertEquals(480, TimelineLayout.widthUnits(TimelineEntry(0L, 3600L), 480))
        // 7_730_941_132_800 = Int.MAX_VALUE * 3600, i.e. exactly the clamp threshold at 1 unit/hour.
        assertEquals(Int.MAX_VALUE, TimelineLayout.widthUnits(TimelineEntry(0L, 7_730_941_132_800L), 1))
        assertFailsWith<IllegalArgumentException> { TimelineLayout.widthUnits(TimelineEntry(0L, 1L), 0) }
        assertFailsWith<IllegalArgumentException> { TimelineLayout.widthUnits(TimelineEntry(0L, 1L), -1) }
    }

    @Test
    fun widthUnitsClampsHugeDurationsWithoutLongOverflow() {
        // True quotients are far above Int.MAX_VALUE; a wrapped 64-bit multiply must still clamp.
        assertEquals(
            Int.MAX_VALUE,
            TimelineLayout.widthUnits(TimelineEntry(0L, 5_000_000_000L), 2_000_000_000),
            "huge duration x huge unitsPerHour overflowed into a tiny width",
        )
        assertEquals(
            Int.MAX_VALUE,
            TimelineLayout.widthUnits(TimelineEntry(0L, Long.MAX_VALUE), 240),
            "Long.MAX_VALUE duration overflowed into a tiny width",
        )
        assertEquals(
            Int.MAX_VALUE,
            TimelineLayout.widthUnits(TimelineEntry(-4_000_000_000L, 4_000_000_000L), Int.MAX_VALUE),
            "large span x Int.MAX_VALUE unitsPerHour overflowed into a tiny width",
        )
    }
}

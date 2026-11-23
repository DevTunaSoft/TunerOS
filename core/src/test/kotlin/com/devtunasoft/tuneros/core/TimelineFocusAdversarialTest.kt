package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Adversarial, deterministic JVM coverage for the allocation-free focus-navigation seam.
 * Complements the happy-path and unique-start coverage in CoreTest (CORE-05/CORE-21).
 * Cell bounds stay small and valid; only the programme start carries extreme Long values.
 */
class TimelineFocusAdversarialTest {
    private fun cell(index: Int, programStart: Long?): TimelineEntry = TimelineEntry(
        index * 10L,
        index * 10L + 10L,
        programStart?.let { GuideProgram("c", "P$index", it, it) },
    )

    @Test fun preferredIndexHandlesExtremeStartsAndMissingMatch() {
        val entries = listOf(
            cell(0, null),                       // gap
            cell(1, Long.MIN_VALUE),             // 1
            cell(2, 50L),                        // 2
            cell(3, Long.MAX_VALUE),             // 3
        )
        assertEquals(1, TimelineFocusPolicy.preferredIndex(entries, Long.MIN_VALUE))
        assertEquals(2, TimelineFocusPolicy.preferredIndex(entries, 50L))
        assertEquals(3, TimelineFocusPolicy.preferredIndex(entries, Long.MAX_VALUE))
        // A non-null preference with no matching start falls back to the first selectable cell.
        assertEquals(1, TimelineFocusPolicy.preferredIndex(entries, 9_999L))
        assertEquals(1, TimelineFocusPolicy.preferredIndex(entries, null))

        val gapsOnly = listOf(cell(0, null), cell(1, null))
        assertEquals(-1, TimelineFocusPolicy.preferredIndex(gapsOnly, Long.MAX_VALUE))
        assertEquals(-1, TimelineFocusPolicy.preferredIndex(gapsOnly, Long.MIN_VALUE))
        assertEquals(-1, TimelineFocusPolicy.preferredIndex(gapsOnly, null))
        assertEquals(-1, TimelineFocusPolicy.preferredIndex(emptyList(), null))
    }

    @Test fun nextIndexAtRowEndsAndWithOutOfRangeCurrentIndex() {
        val entries = listOf(
            cell(0, null),   // 0 gap
            cell(1, 10L),    // 1
            cell(2, 20L),    // 2
            cell(3, null),   // 3 gap
            cell(4, 40L),    // 4
        )
        // -1 sentinel: right enters the row, left stays nowhere.
        assertEquals(1, TimelineFocusPolicy.nextIndex(entries, -1, 1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, -1, -1))
        // Row ends and gap crossing.
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, 1, -1))
        assertEquals(2, TimelineFocusPolicy.nextIndex(entries, 1, 1))
        assertEquals(4, TimelineFocusPolicy.nextIndex(entries, 2, 1))   // skip trailing gap at 3
        assertEquals(2, TimelineFocusPolicy.nextIndex(entries, 4, -1))  // skip gap at 3 to nearest left cell
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, 4, 1))
        // Just-past-the-end index moving back re-enters the row.
        assertEquals(4, TimelineFocusPolicy.nextIndex(entries, entries.size, -1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, entries.size, 1))
        // Negative out-of-range indices never enter the row.
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, -5, 1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, -5, -1))
        // Int-overflow current index must overflow to an out-of-range value and yield -1.
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, Int.MAX_VALUE, 1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, Int.MIN_VALUE, -1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, Int.MAX_VALUE, -1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, Int.MIN_VALUE, 1))
        // Empty/all-gap rows never yield a selectable index.
        assertEquals(-1, TimelineFocusPolicy.nextIndex(emptyList(), -1, 1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(listOf(cell(0, null)), 0, 1))
    }

    @Test fun shiftedIndexWithExtremeLongStarts() {
        val entries = listOf(
            cell(0, null),
            cell(1, Long.MIN_VALUE),
            cell(2, -100L),
            cell(3, 100L),
            cell(4, Long.MAX_VALUE),
        )
        assertEquals(2, TimelineFocusPolicy.shiftedIndex(entries, Long.MIN_VALUE, 1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(entries, Long.MAX_VALUE, -1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(entries, -100L, 1))
        assertEquals(2, TimelineFocusPolicy.shiftedIndex(entries, 100L, -1))
        // Anchor equal to an extreme start selects that same programme in either direction.
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(entries, Long.MIN_VALUE, -1))
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, Long.MAX_VALUE, 1))
        // No matching start: nearest in the direction still wins with extreme bounds.
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, Long.MAX_VALUE - 1, 1))
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(entries, Long.MIN_VALUE + 1, -1))
        // Null anchor: forward selects first, backward selects last selectable.
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(entries, null, 1))
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, null, -1))
    }

    @Test fun shiftedIndexNoMatchingStartFallsBackToDirectionalBoundary() {
        val entries = listOf(cell(0, null), cell(1, 10L), cell(2, 20L), cell(3, 30L))
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(entries, -1_000L, 1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(entries, 1_000L, -1))
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(entries, -1_000L, -1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(entries, 1_000L, 1))
    }

    @Test fun shiftedIndexDuplicateStartsDoNotDependOnEntryOrder() {
        val entries = listOf(
            cell(0, 10L),
            cell(1, 10L),  // duplicate start with cell 0
            cell(2, 20L),
        )
        // Forward from 5: nearest start > 5 is 10; the first of the tied cells wins.
        assertEquals(0, TimelineFocusPolicy.shiftedIndex(entries, 5L, 1))
        // Backward from 25: nearest start < 25 is 20 (unique) -> cell 2.
        assertEquals(2, TimelineFocusPolicy.shiftedIndex(entries, 25L, -1))
        // Forward from 15: nearest start > 15 is 20 (unique) -> cell 2.
        assertEquals(2, TimelineFocusPolicy.shiftedIndex(entries, 15L, 1))
        // Anchor equals duplicated start: backward has no nearer cell, so the first matching cell wins.
        assertEquals(0, TimelineFocusPolicy.shiftedIndex(entries, 10L, -1))
        // Forward from 10 has a genuinely nearer cell (20), which takes precedence over the same-start fallback.
        assertEquals(2, TimelineFocusPolicy.shiftedIndex(entries, 10L, 1))

        val atMax = listOf(cell(0, 10L), cell(1, 20L), cell(2, 20L))
        // At the maximum start there is no nearer forward cell: first of the tied cells wins.
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(atMax, 20L, 1))
    }

    /**
     * Deterministic differential against the directional oracle encoded in CoreTest's
     * shiftedTimelineFocusProperty, extended with MIN/MAX and duplicate starts. This is the
     * adversarial slice the unique-start property never exercises.
     */
    @Test fun shiftedIndexMatchesDirectionalOracleForExtremeAndDuplicateStarts() {
        val random = Random(0xC022L)
        val pool = listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -2L, 0L, 0L,
            7L, 7L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
        )
        repeat(2_000) { iteration ->
            val entries = List(random.nextInt(0, 12)) { i ->
                cell(i, if (random.nextInt(5) == 0) null else pool[random.nextInt(pool.size)])
            }
            val anchor = when (random.nextInt(6)) {
                0 -> null
                1 -> Long.MIN_VALUE
                2 -> Long.MAX_VALUE
                3 -> random.nextLong(Long.MIN_VALUE, Long.MAX_VALUE)
                else -> pool[random.nextInt(pool.size)]
            }
            val available = entries.mapIndexedNotNull { index, entry ->
                entry.program?.let { index to it.startEpochSeconds }
            }
            for (direction in intArrayOf(-1, 1)) {
                val eligible = if (anchor == null) emptyList() else available.filter {
                    if (direction < 0) it.second < anchor else it.second > anchor
                }
                val expected = when {
                    available.isEmpty() -> -1
                    eligible.isNotEmpty() -> if (direction < 0) {
                        // Nearest backward = greatest start below the anchor; ties keep the last index.
                        val bestStart = eligible.maxOf { it.second }
                        eligible.last { it.second == bestStart }.first
                    } else {
                        // Nearest forward = smallest start above the anchor; ties keep the first index.
                        val bestStart = eligible.minOf { it.second }
                        eligible.first { it.second == bestStart }.first
                    }
                    anchor != null && available.any { it.second == anchor } ->
                        available.first { it.second == anchor }.first
                    anchor != null && direction > 0 && available.last().second <= anchor ->
                        available.last().first
                    anchor != null && direction < 0 && available.first().second >= anchor ->
                        available.first().first
                    direction < 0 -> available.last().first
                    else -> available.first().first
                }
                assertEquals(
                    expected,
                    TimelineFocusPolicy.shiftedIndex(entries, anchor, direction),
                    "iteration=$iteration direction=$direction anchor=$anchor available=$available",
                )
            }
        }
    }
}

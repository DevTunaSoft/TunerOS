package com.devtunasoft.tuneros.guide07

import com.devtunasoft.tuneros.GuideRowFocusPolicy
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.TimelineEntry
import com.devtunasoft.tuneros.core.TimelineFocusPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GUIDE-07 deterministic JVM regressions for guide row recycling and focus.
 * Only synthetic channels/programmes are used; no network, storage or device.
 */
class GuideRecycledRowFocusTest {
    private fun denseRow(channelId: String, firstStart: Long): List<TimelineEntry> =
        (0 until 12).map { slot ->
            val start = firstStart + slot * 300L
            TimelineEntry(
                startEpochSeconds = start,
                endEpochSeconds = start + 300L,
                program = GuideProgram(channelId, "Show $slot", start, start + 300L),
            )
        }

    @Test
    fun shiftIntentIsNotLostWhenTheFocusedChannelRowWasRecycledFromAnotherChannel() {
        // The row that receives the window-shift key is rebound to a different channel while
        // ListView is recycling. The intent belongs to the focused channel, not to that View.
        val direction = GuideRowFocusPolicy.shiftDirectionForBind(
            viewPreviousChannelId = "qa-second",
            boundChannelId = "qa-first",
            focusedChannelId = "qa-first",
            pendingDirection = -1,
        )
        assertEquals(-1, direction)
    }

    @Test
    fun shiftIntentNeverLeaksToADifferentChannel() {
        val direction = GuideRowFocusPolicy.shiftDirectionForBind(
            viewPreviousChannelId = "qa-first",
            boundChannelId = "qa-second",
            focusedChannelId = "qa-first",
            pendingDirection = 1,
        )
        assertEquals(0, direction)
    }

    @Test
    fun noFocusOwnerMeansNoShiftDirection() {
        assertEquals(
            0,
            GuideRowFocusPolicy.shiftDirectionForBind("qa-first", "qa-first", null, -1),
        )
    }

    @Test
    fun focusKeepsItsDirectionalAnchorWhenTheFocusedRowWasRecycled() {
        // Old window [4200, 7800): focus on 6000s. New window [1800, 5400) after a LEFT shift.
        // The old anchor is outside the new window, so a plain preferredIndex() would jump to
        // the first show (1800s); a LEFT shift must keep the nearest earlier show (5100s).
        val entries = denseRow("qa-first", 1_800L)
        val direction = GuideRowFocusPolicy.shiftDirectionForBind(
            viewPreviousChannelId = "qa-other",
            boundChannelId = "qa-first",
            focusedChannelId = "qa-first",
            pendingDirection = -1,
        )
        val selected = if (direction != 0) {
            TimelineFocusPolicy.shiftedIndex(entries, 6_000L, direction)
        } else {
            TimelineFocusPolicy.preferredIndex(entries, 6_000L)
        }
        assertEquals(5_100L, entries[selected].program!!.startEpochSeconds)
    }

    @Test
    fun largeChannelRecycleGivesTheDirectionToExactlyOneFocusedRow() {
        val channels = (0 until 160).map { "qa-channel-$it" }
        val focused = "qa-channel-137"
        val recipients = channels.map { channelId ->
            channelId to GuideRowFocusPolicy.shiftDirectionForBind(
                viewPreviousChannelId = "qa-channel-" + ((channelId.removePrefix("qa-channel-").toInt() + 5) % 160),
                boundChannelId = channelId,
                focusedChannelId = focused,
                pendingDirection = -1,
            )
        }
        assertEquals(160, recipients.size)
        assertEquals(listOf(focused), recipients.filter { it.second != 0 }.map { it.first })
    }
}

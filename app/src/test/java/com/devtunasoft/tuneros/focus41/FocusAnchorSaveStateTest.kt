package com.devtunasoft.tuneros.focus41

import com.devtunasoft.tuneros.FocusAnchorState
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideProgram
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FOCUS-41 deterministic JVM regressions for the guide focus anchor across rotation.
 * Synthetic channels/programmes only; no network, storage, device or provider data.
 */
class FocusAnchorSaveStateTest {
    private fun channel(id: String = "qa-channel-7") = Channel(
        id = id,
        name = "QA channel name that must not be persisted",
        streamUrl = "qa-scheme://no-provider/stream",
        guideId = "qa-guide-7",
    )

    private fun program(start: Long = 1_700_000_000L) = GuideProgram(
        channelId = "qa-guide-7",
        title = "QA programme title that must not be persisted",
        startEpochSeconds = start,
        endEpochSeconds = start + 1_800L,
    )

    @Test
    fun onSaveInstanceStateKeepsFocusAnchorWithoutChannelNameOrUrl() {
        val activity = Focus41Fixture.activity()
        Focus41Fixture.setField(activity, "focusedChannel", channel())
        Focus41Fixture.setField(activity, "focusedProgram", program())
        Focus41Fixture.setField(activity, "guideWindowStart", 1_699_999_800L)

        val out = Focus41Fixture.saveState(activity)
        val values = out.values()

        // The anchor is a bounded channel id plus a programme start: enough to find the same
        // row again, with no EPG payload, name or URL in the Bundle.
        assertTrue(
            "saved state must carry the synthetic channel id",
            values.any { it == "qa-channel-7" },
        )
        assertTrue(
            "saved state must carry the synthetic programme start",
            values.any { it == 1_700_000_000L },
        )
        assertFalse(
            "channel names must never enter saved state",
            values.any { it == "QA channel name that must not be persisted" },
        )
        assertFalse(
            "programme titles must never enter saved state",
            values.any { it == "QA programme title that must not be persisted" },
        )
        assertFalse(
            "stream URLs must never enter saved state",
            values.any { it is String && it.contains("qa-scheme://") },
        )
        assertTrue("saved state must stay small", out.size <= 6)
    }

    @Test
    fun onSaveInstanceStateWritesNoAnchorWhenNothingIsFocused() {
        val out = Focus41Fixture.saveState(Focus41Fixture.activity())
        assertFalse(out.containsKey(FocusAnchorState.KEY_CHANNEL))
        assertFalse(out.containsKey(FocusAnchorState.KEY_PROGRAM_START))
    }

    @Test
    fun onSaveInstanceStateRefusesOversizedChannelId() {
        val activity = Focus41Fixture.activity()
        Focus41Fixture.setField(activity, "focusedChannel", channel("q".repeat(129)))
        Focus41Fixture.setField(activity, "focusedProgram", program())

        val out = Focus41Fixture.saveState(activity)
        assertFalse("unbounded channel id must not be saved", out.containsKey(FocusAnchorState.KEY_CHANNEL))
        assertFalse(out.containsKey(FocusAnchorState.KEY_PROGRAM_START))
    }

    @Test
    fun onSaveInstanceStateRefusesProgramWithoutChannel() {
        val activity = Focus41Fixture.activity()
        Focus41Fixture.setField(activity, "focusedProgram", program())

        val out = Focus41Fixture.saveState(activity)
        assertFalse("a half anchor is not restorable", out.containsKey(FocusAnchorState.KEY_PROGRAM_START))
    }
}

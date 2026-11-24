package com.devtunasoft.tuneros.focus41

import android.os.Bundle
import com.devtunasoft.tuneros.FocusAnchor
import com.devtunasoft.tuneros.FocusAnchorState
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** FOCUS-41 restore-side JVM regressions, all with synthetic channels and programme times. */
class FocusAnchorRestoreTest {
    private val channel = Channel(
        id = "qa-channel-7",
        name = "QA name never logged",
        streamUrl = "qa-scheme://no-provider/stream",
        guideId = "qa-guide-7",
    )
    private val program = GuideProgram(
        channelId = "qa-guide-7",
        title = "QA title never logged",
        startEpochSeconds = 1_700_000_000L,
        endEpochSeconds = 1_700_001_800L,
    )
    private val guide = GuideSnapshot(listOf(program), mapOf("qa-guide-7" to "QA name never logged"))

    private fun savedBundle(): Bundle {
        val out = Bundle()
        FocusAnchorState.save(out, channel, program)
        return out
    }

    @Test
    fun readRestoresTheSavedChannelIdAndProgrammeStart() {
        val anchor = FocusAnchorState.read(savedBundle())
        assertNotNull(anchor)
        assertEquals("qa-channel-7", anchor!!.channelId)
        assertEquals(1_700_000_000L, anchor.programStartSeconds)
    }

    @Test
    fun readIgnoresNullBlankOversizedAndNonPositiveState() {
        assertNull(FocusAnchorState.read(null))

        val blank = Bundle()
        blank.putString(FocusAnchorState.KEY_CHANNEL, "   ")
        blank.putLong(FocusAnchorState.KEY_PROGRAM_START, 1_700_000_000L)
        assertNull(FocusAnchorState.read(blank))

        val oversized = Bundle()
        oversized.putString(FocusAnchorState.KEY_CHANNEL, "q".repeat(129))
        oversized.putLong(FocusAnchorState.KEY_PROGRAM_START, 1_700_000_000L)
        assertNull(FocusAnchorState.read(oversized))

        val nonPositive = Bundle()
        nonPositive.putString(FocusAnchorState.KEY_CHANNEL, "qa-channel-7")
        nonPositive.putLong(FocusAnchorState.KEY_PROGRAM_START, 0L)
        assertNull(FocusAnchorState.read(nonPositive))
    }

    @Test
    fun applyPendingAnchorRestoresBothFocusFieldsWhenDataIsAvailable() {
        val activity = Focus41Fixture.activity()
        Focus41Fixture.setField(activity, "channels", listOf(channel))
        Focus41Fixture.setField(activity, "guide", guide)
        Focus41Fixture.setField(activity, "pendingFocusAnchor", FocusAnchor("qa-channel-7", 1_700_000_000L))

        Focus41Fixture.invokeNoArg(activity, "applyPendingFocusAnchor")

        assertEquals(channel, Focus41Fixture.getField(activity, "focusedChannel"))
        assertEquals(program, Focus41Fixture.getField(activity, "focusedProgram"))
        assertNull("anchor must not be retained after a successful restore",
            Focus41Fixture.getField(activity, "pendingFocusAnchor"))
    }

    @Test
    fun applyPendingAnchorWaitsForGuideAndThenRestores() {
        val activity = Focus41Fixture.activity()
        Focus41Fixture.setField(activity, "channels", listOf(channel))
        Focus41Fixture.setField(activity, "pendingFocusAnchor", FocusAnchor("qa-channel-7", 1_700_000_000L))

        Focus41Fixture.invokeNoArg(activity, "applyPendingFocusAnchor")
        assertEquals(channel, Focus41Fixture.getField(activity, "focusedChannel"))
        assertNotNull("guide may still be loading", Focus41Fixture.getField(activity, "pendingFocusAnchor"))

        Focus41Fixture.setField(activity, "guide", guide)
        Focus41Fixture.invokeNoArg(activity, "applyPendingFocusAnchor")
        assertEquals(program, Focus41Fixture.getField(activity, "focusedProgram"))
        assertNull(Focus41Fixture.getField(activity, "pendingFocusAnchor"))
    }

    @Test
    fun unknownChannelDoesNotOverwriteAnExistingFocus() {
        val activity = Focus41Fixture.activity()
        val current = channel.copy(id = "qa-channel-8")
        Focus41Fixture.setField(activity, "focusedChannel", current)
        Focus41Fixture.setField(activity, "channels", listOf(current))
        Focus41Fixture.setField(activity, "pendingFocusAnchor", FocusAnchor("qa-channel-7", 1_700_000_000L))

        Focus41Fixture.invokeNoArg(activity, "applyPendingFocusAnchor")

        assertEquals(current, Focus41Fixture.getField(activity, "focusedChannel"))
        assertNotNull("unresolvable anchor stays pending for a later load, never overwrites focus",
            Focus41Fixture.getField(activity, "pendingFocusAnchor"))
    }
}

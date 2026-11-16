package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerReusePolicyTest {
    private val providerA = mapOf("Referer" to "https://provider-a.example/")
    private val providerB = mapOf("Referer" to "https://provider-b.example/")

    @Test fun aLiveChannelWithTheSameHeadersReusesThePlayer() {
        assertTrue(PlayerReusePolicy.canReuse(providerA, providerA.toMap(), playingRecording = false))
    }

    @Test fun channelsWithoutHeadersShareAPlayerOnlyWhenBothHaveNoHeaders() {
        assertTrue(PlayerReusePolicy.canReuse(emptyMap(), emptyMap(), playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(emptyMap(), providerA, playingRecording = false))
    }

    @Test fun differentHeadersNeverReuseThePlayer() {
        assertFalse(PlayerReusePolicy.canReuse(providerA, providerB, playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(providerA, emptyMap(), playingRecording = false))
    }

    @Test fun withoutACurrentPlayerNothingIsReused() {
        assertFalse(PlayerReusePolicy.canReuse(null, providerA, playingRecording = false))
    }

    @Test fun aPlayerThatIsPlayingARecordingIsNeverReused() {
        assertFalse(PlayerReusePolicy.canReuse(providerA, providerA, playingRecording = true))
    }

    @Test fun aRapidSequenceAlwaysLeavesThePlayerWithTheChannelsOwnHeaders() {
        val switches = listOf(
            providerA, providerA, providerB, providerB, providerA, emptyMap<String, String>(), providerA, providerB,
        )
        var current: Map<String, String>? = null
        var recreations = 0
        for (next in switches) {
            if (PlayerReusePolicy.canReuse(current, next, playingRecording = false)) {
                // Reuse keeps the player, which already carries exactly these headers.
                assertEquals(next, current)
            } else {
                recreations++
                current = next.toMap()
            }
            assertEquals(next, current, "player headers must match the channel after every switch")
        }
        assertEquals(6, recreations)
    }
}

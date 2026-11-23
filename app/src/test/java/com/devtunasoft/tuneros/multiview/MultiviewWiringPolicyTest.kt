package com.devtunasoft.tuneros.multiview

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the exact policy MainActivity uses around the controller: a bounded slot claim in the
 * factory, and a slot release from the player's release path. No Android classes are involved, so the
 * no-leak / one-audible invariants are verified on the JVM.
 */
class MultiviewWiringPolicyTest {
    private class TrackedPlayer {
        var lastVolume = -1f
        var releaseCount = 0

        fun setVolume(value: Float) {
            lastVolume = value
        }

        fun release() {
            releaseCount++
        }
    }

    private class Harness {
        val policy = MultiviewUiPolicy().also { it.enable() }
        val created = ArrayList<TrackedPlayer>()
        val byId = HashMap<String, TrackedPlayer>()

        val controller = MultiviewTileController(MultiviewPlayerFactory { tileId ->
            val slot = policy.acquireSlot(tileId)
            val player = TrackedPlayer()
            created += player
            byId[tileId] = player
            object : MultiviewPlayer {
                override fun setVolume(volume: Float) = player.setVolume(volume)
                override fun release() {
                    player.release()
                    policy.releaseSlot(tileId)
                }
            }
        })

        fun open(key: String) = controller.open(MultiviewUiPolicy.tileIdFor(key))
        fun focus(key: String) = controller.focus(MultiviewUiPolicy.tileIdFor(key))

        fun assertBounded() {
            val live = created.count { it.releaseCount == 0 }
            assertEquals(controller.playerCount, live)
            assertEquals(live, policy.liveTileCount)
            assertTrue(created.all { it.releaseCount <= 1 })
            val occupied = listOfNotNull(policy.tileAt(0), policy.tileAt(1))
            assertEquals(occupied.distinct(), occupied)
            occupied.forEach { assertEquals(it, policy.tileAt(policy.slotOf(it)!!)) }
            controller.primaryTileId?.let {
                assertEquals(it, policy.tileAt(policy.slotOf(it)!!))
                assertEquals(1f, byId[it]!!.lastVolume, 0f)
            }
            controller.secondaryTileId?.let {
                assertEquals(0f, byId[it]!!.lastVolume, 0f)
            }
            assertEquals(controller.openTileIds.size <= MultiviewTileController.MAX_TILES, true)
        }
    }

    @Test
    fun twoOpensOccupyTwoDistinctSlotsWithExactlyOneAudibleTile() {
        val h = Harness()
        h.open("alpha")
        assertEquals(1, h.policy.liveTileCount)
        assertEquals("alpha", h.controller.audibleTileId)
        h.open("beta")
        assertEquals(2, h.policy.liveTileCount)
        assertFalse(h.policy.slotOf("alpha") == h.policy.slotOf("beta"))
        assertEquals("alpha", h.controller.audibleTileId)
        h.assertBounded()
    }

    @Test
    fun focusingTheMutedTileKeepsItsSlotAndFreesOnlyTheOldPrimarySlot() {
        val h = Harness()
        h.open("alpha")
        h.open("beta")
        val promotedSlot = h.policy.slotOf("beta")
        h.focus("beta")
        assertEquals("beta", h.controller.audibleTileId)
        assertEquals(promotedSlot, h.policy.slotOf("beta"))
        assertNull(h.policy.slotOf("alpha"))
        assertEquals(1, h.policy.liveTileCount)
        assertEquals(1, h.byId["alpha"]!!.releaseCount)
        assertEquals(0, h.byId["beta"]!!.releaseCount)
        h.open("gamma")
        assertEquals(0f, h.byId["gamma"]!!.lastVolume, 0f)
        assertEquals("beta", h.controller.audibleTileId)
        h.assertBounded()
    }

    @Test
    fun rapidSelectionAndFocusSwitchingNeverLeaksPlayersOrSlots() {
        val h = Harness()
        val keys = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val random = Random(20_261_122)
        repeat(2_000) {
            when (random.nextInt(3)) {
                0 -> h.open(keys[random.nextInt(keys.size)])
                1 -> h.focus(keys[random.nextInt(keys.size)])
                else -> if (random.nextBoolean()) h.controller.pause() else h.controller.stop()
            }
            h.assertBounded()
        }
    }

    @Test
    fun reopeningTheAudibleTileCreatesNoPlayerAndNoSlot() {
        val h = Harness()
        h.open("alpha")
        val before = h.created.size
        h.open("alpha")
        assertEquals(before, h.created.size)
        assertEquals(1, h.policy.liveTileCount)
        h.assertBounded()
    }

    @Test
    fun strictCapacityRoutingRefusesThirdTileAndReopensAfterFocus() {
        val h = Harness()
        assertEquals(
            OpenResult.PRIMARY_OPENED,
            h.controller.openWithinCapacity(MultiviewUiPolicy.tileIdFor("a")).result,
        )
        assertEquals(
            OpenResult.SECONDARY_OPENED,
            h.controller.openWithinCapacity(MultiviewUiPolicy.tileIdFor("b")).result,
        )
        val before = h.created.size
        val refused = h.controller.openWithinCapacity(MultiviewUiPolicy.tileIdFor("c"))
        assertEquals(OpenResult.CAPACITY_REFUSED, refused.result)
        assertEquals("a", refused.fallbackAudibleTileId)
        assertEquals(before, h.created.size)
        assertEquals(2, h.policy.liveTileCount)
        h.assertBounded()

        h.focus("b")
        assertEquals("b", h.controller.audibleTileId)
        assertEquals(1, h.policy.liveTileCount)
        assertEquals(
            OpenResult.SECONDARY_OPENED,
            h.controller.openWithinCapacity(MultiviewUiPolicy.tileIdFor("c")).result,
        )
        assertEquals("c", h.controller.secondaryTileId)
        h.assertBounded()
    }

    @Test
    fun pauseAndStopReleaseAllPlayersAndSlotsDeterministically() {
        val h = Harness()
        h.open("alpha")
        h.open("beta")
        assertTrue(h.controller.pause())
        assertFalse(h.controller.pause())
        assertEquals(1, h.byId["beta"]!!.releaseCount)
        assertNull(h.policy.tileAt(1))
        h.assertBounded()

        h.open("gamma")
        h.controller.stop()
        h.controller.stop()
        assertTrue(h.created.all { it.releaseCount == 1 })
        assertEquals(0, h.policy.liveTileCount)
        assertEquals(0, h.controller.playerCount)
        assertNull(h.controller.audibleTileId)
        h.policy.disable()
        assertFalse(h.policy.isEnabled)
        h.assertBounded()
    }
}

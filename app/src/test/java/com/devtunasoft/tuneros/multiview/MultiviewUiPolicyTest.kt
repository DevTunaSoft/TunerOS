package com.devtunasoft.tuneros.multiview

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewUiPolicyTest {
    @Test
    fun aFreshPolicyIsAlwaysOff() {
        assertFalse(MultiviewUiPolicy().isEnabled)
        assertFalse(MultiviewUiPolicy().isEnabled)
        assertEquals(0, MultiviewUiPolicy().liveTileCount)
    }

    @Test
    fun optInIsExplicitAndDisableClearsSlots() {
        val policy = MultiviewUiPolicy()
        policy.enable()
        assertTrue(policy.isEnabled)
        policy.acquireSlot("alpha")
        policy.acquireSlot("beta")
        assertEquals(2, policy.liveTileCount)
        policy.disable()
        assertFalse(policy.isEnabled)
        assertEquals(0, policy.liveTileCount)
        assertNull(policy.tileAt(0))
        assertNull(policy.tileAt(1))
        // Re-enabling starts from an empty pool, not from the previous session's tiles.
        policy.enable()
        assertEquals(0, policy.liveTileCount)
    }

    @Test
    fun lifecycleStopClearsSlotsWithoutChangingOptIn() {
        val policy = MultiviewUiPolicy()
        policy.enable()
        policy.acquireSlot("alpha")
        policy.acquireSlot("beta")
        policy.clearSlots()
        assertEquals(0, policy.liveTileCount)
        assertTrue(policy.isEnabled)
    }

    @Test
    fun tileIdForIsBoundedDeterministicAndNonBlank() {
        val ordinary = MultiviewUiPolicy.tileIdFor("channel-7")
        assertEquals("channel-7", ordinary)
        assertEquals(ordinary, MultiviewUiPolicy.tileIdFor("channel-7"))
        assertNotEquals(ordinary, MultiviewUiPolicy.tileIdFor("channel-8"))

        val longKey = "x".repeat(200)
        val reduced = MultiviewUiPolicy.tileIdFor(longKey)
        assertTrue(reduced.isNotBlank())
        assertTrue(reduced.length <= 128) // the controller's valid-id limit
        assertTrue(reduced.length <= 10)
        assertEquals(reduced, MultiviewUiPolicy.tileIdFor(longKey))

        val blank = MultiviewUiPolicy.tileIdFor("   ")
        assertTrue(blank.isNotBlank())
        assertTrue(blank.length <= 10)
    }

    @Test
    fun slotsAreBoundedToTwoAndRecycledOnRelease() {
        val slots = TileSlotPool()
        assertEquals(0, slots.acquire("a"))
        assertEquals(1, slots.acquire("b"))
        assertEquals(2, slots.liveCount)
        assertEquals("a", slots.tileAt(0))
        assertEquals("b", slots.tileAt(1))
        assertEquals(0, slots.slotOf("a"))
        assertEquals(1, slots.slotOf("b"))
        assertNull(slots.slotOf("c"))
        assertEquals(0, slots.acquire("a")) // reacquiring an owned tile is not a new slot
        assertEquals(1, slots.acquire("b"))
        var refused = false
        try {
            slots.acquire("c")
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertTrue(refused)
        assertTrue(slots.release("a"))
        assertFalse(slots.release("a")) // idempotent
        assertFalse(slots.release("never-acquired"))
        assertEquals(0, slots.acquire("c")) // released slot is reused, pool never grows
        assertEquals(2, slots.liveCount)
        slots.clear()
        assertEquals(0, slots.liveCount)
    }

    @Test
    fun repeatedSwitchingNeverOccupiesMoreThanTwoSlots() {
        val slots = TileSlotPool()
        val random = Random(20_261_123)
        repeat(2_000) {
            val id = "tile-" + random.nextInt(8)
            when (random.nextInt(3)) {
                0 -> try {
                    slots.acquire(id)
                } catch (_: IllegalStateException) {
                    // A bounded pool refuses a third tile; that is the intended ceiling.
                }
                1 -> slots.release(id)
                else -> slots.clear()
            }
            assertTrue(slots.liveCount <= MultiviewTileController.MAX_TILES)
            val occupied = listOfNotNull(slots.tileAt(0), slots.tileAt(1))
            assertEquals(occupied.distinct(), occupied)
            occupied.forEach { assertEquals(it, slots.tileAt(slots.slotOf(it)!!)) }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankTileIdsAreRejected() {
        TileSlotPool().acquire("  ")
    }
}

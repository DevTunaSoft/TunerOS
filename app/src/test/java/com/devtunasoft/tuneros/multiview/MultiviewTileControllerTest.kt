package com.devtunasoft.tuneros.multiview

// Integrator note: app/build.gradle.kts needs testImplementation("junit:junit:4.13.2") for this
// JUnit 4 suite to compile under :app:testDebugUnitTest (Gradle dependency is integrator-owned).
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewTileControllerTest {
    private class FakePlayer : MultiviewPlayer {
        var lastVolume = -1f
        var releaseCount = 0
        var failOnSetVolume = false
        override fun setVolume(volume: Float) {
            if (failOnSetVolume) throw IllegalStateException("volume change refused")
            lastVolume = volume
        }
        override fun release() {
            releaseCount++
        }
    }

    private class Factory : MultiviewPlayerFactory {
        val created = ArrayList<FakePlayer>()
        val byId = HashMap<String, FakePlayer>()
        var failNextCreate = false
        var failNextVolume = false
        override fun create(tileId: String): FakePlayer {
            if (failNextCreate) {
                failNextCreate = false
                throw IllegalStateException("decoder capacity exhausted")
            }
            return FakePlayer().also {
                it.failOnSetVolume = failNextVolume
                failNextVolume = false
                created += it
                byId[tileId] = it
            }
        }
    }

    private fun controller(): Pair<MultiviewTileController, Factory> {
        val factory = Factory()
        return MultiviewTileController(factory) to factory
    }

    @Test
    fun firstOpenCreatesOneAudiblePrimary() {
        val (controller, factory) = controller()
        assertEquals(OpenResult.PRIMARY_OPENED, controller.open("a"))
        assertEquals("a", controller.primaryTileId)
        assertEquals("a", controller.audibleTileId)
        assertNull(controller.secondaryTileId)
        assertEquals(1, controller.playerCount)
        assertEquals(1f, factory.byId["a"]!!.lastVolume, 0f)
        assertTrue(controller.isAudible("a"))
    }

    @Test
    fun secondOpenCreatesMutedSecondaryAndAudioStaysExclusive() {
        val (controller, factory) = controller()
        controller.open("a")
        assertEquals(OpenResult.SECONDARY_OPENED, controller.open("b"))
        assertEquals("b", controller.secondaryTileId)
        assertEquals("a", controller.audibleTileId)
        assertEquals(2, controller.playerCount)
        assertEquals(1f, factory.byId["a"]!!.lastVolume, 0f)
        assertEquals(0f, factory.byId["b"]!!.lastVolume, 0f)
        assertTrue(controller.isAudible("a"))
        assertTrue(controller.isMuted("b"))
        assertFalse(controller.isAudible("b"))
    }

    @Test
    fun manyOpensKeepOnlyTwoPlayersLiveAndReopeningCreatesNone() {
        val (controller, factory) = controller()
        repeat(20) { index -> controller.open("tile-$index") }
        assertEquals(MultiviewTileController.MAX_TILES, controller.playerCount)
        // Each secondary replacement creates one player and releases the old one: only two stay live.
        assertEquals(20, factory.created.size)
        assertEquals(18, factory.created.count { it.releaseCount == 1 })
        assertEquals(2, factory.created.count { it.releaseCount == 0 })
        assertEquals(OpenResult.ALREADY_OPEN, controller.open(controller.primaryTileId!!))
        assertEquals(OpenResult.ALREADY_OPEN, controller.open(controller.secondaryTileId!!))
        assertEquals(2, controller.playerCount)
        assertEquals(20, factory.created.size)
    }

    @Test
    fun replacingSecondaryReleasesOnlyTheOldSecondaryOnce() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        assertEquals(OpenResult.SECONDARY_REPLACED, controller.open("c"))
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertEquals(0, factory.byId["a"]!!.releaseCount)
        assertEquals("c", controller.secondaryTileId)
        assertEquals(0f, factory.byId["c"]!!.lastVolume, 0f)
        assertEquals("a", controller.audibleTileId)
        assertEquals(OpenResult.SECONDARY_REPLACED, controller.open("d"))
        assertEquals(1, factory.byId["c"]!!.releaseCount)
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertEquals(0, factory.byId["a"]!!.releaseCount)
        assertEquals(2, controller.playerCount)
    }

    @Test
    fun focusPromotesMutedSecondaryAndReleasesOldPrimary() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        assertEquals(FocusResult.FOCUSED, controller.focus("b"))
        assertEquals("b", controller.audibleTileId)
        assertEquals("b", controller.primaryTileId)
        assertNull(controller.secondaryTileId)
        assertEquals(1, controller.playerCount)
        assertEquals(1, factory.byId["a"]!!.releaseCount)
        assertEquals(0, factory.byId["b"]!!.releaseCount)
        assertEquals(1f, factory.byId["b"]!!.lastVolume, 0f)
        assertFalse(controller.isMuted("b"))
        // Promotion reuses the existing muted player: no third player was built.
        assertEquals(2, factory.created.size)
    }

    @Test
    fun focusRejectsUnknownBlankAndAlreadyAudibleWithoutChange() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        assertEquals(FocusResult.ALREADY_AUDIBLE, controller.focus("a"))
        assertEquals(FocusResult.NOT_OPEN, controller.focus("missing"))
        assertEquals(FocusResult.INVALID_ID, controller.focus(" "))
        assertEquals("a", controller.audibleTileId)
        assertEquals("b", controller.secondaryTileId)
        assertEquals(2, controller.playerCount)
        assertTrue(factory.created.none { it.releaseCount > 0 })
    }

    @Test
    fun pauseReleasesOnlyTheMutedSecondaryAndIsIdempotent() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        assertTrue(controller.pause())
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertEquals(0, factory.byId["a"]!!.releaseCount)
        assertNull(controller.secondaryTileId)
        assertEquals("a", controller.audibleTileId)
        assertFalse(controller.pause())
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertEquals(1, controller.playerCount)
    }

    @Test
    fun stopReleasesBothPlayersAndIsIdempotent() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        controller.stop()
        assertEquals(1, factory.byId["a"]!!.releaseCount)
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertEquals(0, controller.playerCount)
        assertNull(controller.audibleTileId)
        assertNull(controller.primaryTileId)
        assertNull(controller.secondaryTileId)
        controller.stop()
        assertEquals(1, factory.byId["a"]!!.releaseCount)
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        // Capacity is fully restored after stop.
        assertEquals(OpenResult.PRIMARY_OPENED, controller.open("c"))
        assertEquals("c", controller.audibleTileId)
    }

    @Test
    fun invalidIdsNeverCreateAPlayer() {
        val (controller, factory) = controller()
        assertEquals(OpenResult.INVALID_ID, controller.open(" "))
        assertEquals(OpenResult.INVALID_ID, controller.open("x".repeat(129)))
        assertEquals(0, factory.created.size)
        assertEquals(0, controller.playerCount)
        assertNull(controller.audibleTileId)
    }

    @Test
    fun invariantsHoldAcrossADeterministicMixedSequence() {
        val random = Random(20_261_122)
        val ids = listOf("a", "b", "c", "d", " ")
        val (controller, factory) = controller()
        repeat(2_000) {
            when (random.nextInt(4)) {
                0 -> controller.open(ids[random.nextInt(ids.size)])
                1 -> controller.focus(ids[random.nextInt(ids.size)])
                2 -> controller.pause()
                else -> controller.stop()
            }
            assertTrue(controller.playerCount <= MultiviewTileController.MAX_TILES)
            val open = controller.openTileIds
            assertEquals(open.distinct(), open)
            assertEquals(open.isEmpty(), controller.audibleTileId == null)
            assertTrue(controller.audibleTileId == null || controller.audibleTileId in open)
            val live = factory.created.filter { it.releaseCount == 0 }
            assertEquals(controller.playerCount, live.size)
            assertTrue(factory.created.all { it.releaseCount <= 1 })
            controller.primaryTileId?.let { assertEquals(1f, factory.byId[it]!!.lastVolume, 0f) }
            controller.secondaryTileId?.let { assertEquals(0f, factory.byId[it]!!.lastVolume, 0f) }
        }
    }

    @Test
    fun failedSecondaryCreationLeavesOnlyTheAudiblePrimary() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        factory.failNextCreate = true
        try {
            controller.open("c")
        } catch (expected: IllegalStateException) {
            // MV-01 propagates the provider failure; MV-02 must also clean up after it.
        }
        // The released old secondary must not remain registered as a live tile.
        assertEquals(1, controller.playerCount)
        assertEquals("a", controller.audibleTileId)
        assertNull(controller.secondaryTileId)
        assertTrue(controller.openTileIds == listOf("a"))
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertEquals(0, factory.byId["a"]!!.releaseCount)
        assertEquals(1f, factory.byId["a"]!!.lastVolume, 0f)
        // A failed attempt must not consume decoder capacity or leave a third player alive.
        assertEquals(2, factory.created.size)
    }

    @Test
    fun focusFailureReleasesBothTilesAndLeavesZeroAudible() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        factory.byId["b"]!!.failOnSetVolume = true
        try {
            controller.focus("b")
        } catch (expected: IllegalStateException) {
            // MV-01 propagates the volume failure; MV-02 must release every tile on that failure.
        }
        assertNull(controller.audibleTileId)
        assertEquals(0, controller.playerCount)
        assertTrue(controller.openTileIds.isEmpty())
        assertEquals(1, factory.byId["a"]!!.releaseCount)
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertFalse(controller.isAudible("a"))
        assertFalse(controller.isAudible("b"))
    }

    @Test
    fun thirdTileIsRefusedWithinCapacityWithStructuredAudibleFallback() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        val attempt = controller.openWithinCapacity("c")
        assertEquals(OpenResult.CAPACITY_REFUSED, attempt.result)
        assertEquals("a", attempt.fallbackAudibleTileId)
        assertEquals("a", controller.audibleTileId)
        assertEquals("b", controller.secondaryTileId)
        assertEquals(2, controller.playerCount)
        // Refusal allocates no third decoder and releases nothing.
        assertEquals(2, factory.created.size)
        assertTrue(factory.created.none { it.releaseCount > 0 })
        assertEquals(OpenResult.ALREADY_OPEN, controller.openWithinCapacity("a").result)
        assertEquals(OpenResult.ALREADY_OPEN, controller.openWithinCapacity("b").result)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun strictCapacityOpenUsesFreeSlotAfterFocusFailureAndAfterStop() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        factory.byId["b"]!!.failOnSetVolume = true
        assertEquals(FocusResult.RELEASED_ON_FAILURE, controller.focus("b"))
        assertNull(controller.audibleTileId)
        controller.stop()
        // No tile is released twice by the failure cleanup followed by stop.
        assertTrue(factory.created.all { it.releaseCount == 1 })
        assertEquals(OpenResult.PRIMARY_OPENED, controller.openWithinCapacity("c").result)
        assertEquals("c", controller.audibleTileId)
        assertEquals(1, controller.playerCount)
    }

    @Test
    fun providerCreationFailureReturnsStructuredResultAndKeepsOneAudibleTile() {
        val (controller, factory) = controller()
        controller.open("a")
        controller.open("b")
        factory.failNextCreate = true
        val result = controller.open("c")
        assertEquals(OpenResult.CREATE_FAILED, result)
        assertEquals("a", controller.audibleTileId)
        assertNull(controller.secondaryTileId)
        // Capacity is restored: the next strict open may create a secondary again.
        assertEquals(OpenResult.SECONDARY_OPENED, controller.openWithinCapacity("d").result)
        assertEquals("d", controller.secondaryTileId)
    }

    @Test
    fun primaryCreationFailureLeavesZeroAudibleAndConsumesNoCapacity() {
        val (controller, factory) = controller()
        factory.failNextCreate = true
        val attempt = controller.openWithinCapacity("a")
        assertEquals(OpenResult.CREATE_FAILED, attempt.result)
        assertNull(attempt.fallbackAudibleTileId)
        assertNull(controller.audibleTileId)
        assertEquals(0, controller.playerCount)
        assertEquals(0, factory.created.size)
        assertEquals(OpenResult.INVALID_ID, controller.openWithinCapacity(" ").result)
        assertEquals(OpenResult.PRIMARY_OPENED, controller.openWithinCapacity("b").result)
        assertEquals("b", controller.audibleTileId)
    }

    @Test
    fun secondaryVolumeFailureReleasesFailedTileAndKeepsPrimaryAudible() {
        val (controller, factory) = controller()
        controller.open("a")
        factory.failNextVolume = true
        val attempt = controller.openWithinCapacity("b")
        assertEquals(OpenResult.CREATE_FAILED, attempt.result)
        assertEquals("a", attempt.fallbackAudibleTileId)
        assertEquals("a", controller.audibleTileId)
        assertNull(controller.secondaryTileId)
        assertEquals(1, controller.playerCount)
        assertEquals(1, factory.byId["b"]!!.releaseCount)
        assertEquals(0, factory.byId["a"]!!.releaseCount)
    }
}

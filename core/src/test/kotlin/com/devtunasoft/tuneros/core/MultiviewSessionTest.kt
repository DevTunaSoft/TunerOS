package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiviewSessionTest {
    private val admission = MultiviewSession.Admission.ADMITTED

    @Test fun firstTileIsAudibleAndAdmissionStopsAtTheDecoderLimit() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 2, providerConnections = 4))
        assertEquals(admission, session.open("a"))
        assertEquals("a", session.audibleTile)
        assertEquals(admission, session.open("b"))
        assertEquals("a", session.audibleTile)
        assertEquals(MultiviewSession.Admission.AT_CAPACITY, session.open("c"))
        assertEquals(listOf("a", "b"), session.openTiles)
    }

    @Test fun providerLimitCapsTilesEvenWithDecoderHeadroom() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 4, providerConnections = 1))
        assertEquals(admission, session.open("a"))
        assertEquals(MultiviewSession.Admission.AT_CAPACITY, session.open("b"))
    }

    @Test fun zeroCapacityAdmitsNothingAndHasNoAudibleTile() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 0, providerConnections = 4))
        assertEquals(MultiviewSession.Admission.AT_CAPACITY, session.open("a"))
        assertNull(session.audibleTile)
    }

    @Test fun neverMoreThanFourTilesWhateverTheCapacity() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 9, providerConnections = 9, userLimit = 9))
        assertEquals(4, session.maxTiles)
        for (id in listOf("a", "b", "c", "d")) assertEquals(admission, session.open(id))
        assertEquals(MultiviewSession.Admission.AT_CAPACITY, session.open("e"))
    }

    @Test fun duplicateAndInvalidIdsAreRejectedWithoutChangingTheSession() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 2, providerConnections = 2))
        session.open("a")
        assertEquals(MultiviewSession.Admission.DUPLICATE, session.open("a"))
        assertEquals(MultiviewSession.Admission.INVALID_ID, session.open(" "))
        assertEquals(MultiviewSession.Admission.INVALID_ID, session.open("x".repeat(129)))
        assertEquals(listOf("a"), session.openTiles)
    }

    @Test fun focusMovesAudioToExactlyOneOpenTile() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 2, providerConnections = 2))
        session.open("a")
        session.open("b")
        assertTrue(session.focusAudio("b"))
        assertEquals("b", session.audibleTile)
        assertFalse(session.focusAudio("missing"))
        assertEquals("b", session.audibleTile)
    }

    @Test fun closingTheAudibleTileHandsAudioToTheTileThatTookItsPlace() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 4, providerConnections = 4))
        for (id in listOf("a", "b", "c")) session.open(id)
        session.focusAudio("b")
        assertTrue(session.close("b"))
        assertEquals("c", session.audibleTile)
    }

    @Test fun closingTheLastTileHandsAudioToTheNewLastTile() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 4, providerConnections = 4))
        for (id in listOf("a", "b", "c")) session.open(id)
        session.focusAudio("c")
        assertTrue(session.close("c"))
        assertEquals("b", session.audibleTile)
    }

    @Test fun closingAnEarlierTileKeepsTheSameTileAudible() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 4, providerConnections = 4))
        for (id in listOf("a", "b", "c")) session.open(id)
        session.focusAudio("c")
        assertTrue(session.close("a"))
        assertEquals("c", session.audibleTile)
        assertEquals(listOf("b", "c"), session.openTiles)
    }

    @Test fun closingEveryTileLeavesNoAudibleTileAndFreesCapacity() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 1, providerConnections = 1))
        session.open("a")
        assertTrue(session.close("a"))
        assertNull(session.audibleTile)
        assertEquals(admission, session.open("b"))
        assertEquals("b", session.audibleTile)
    }

    @Test fun closingAnUnknownTileChangesNothing() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 2, providerConnections = 2))
        session.open("a")
        assertFalse(session.close("missing"))
        assertEquals(listOf("a"), session.openTiles)
        assertEquals("a", session.audibleTile)
    }

    @Test fun invariantsHoldAcrossADeterministicRunOfOperations() {
        val random = Random(20_261_116)
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 3, providerConnections = 4))
        val ids = listOf("a", "b", "c", "d", "e")
        repeat(10_000) {
            val id = ids[random.nextInt(ids.size)]
            when (random.nextInt(3)) {
                0 -> session.open(id)
                1 -> session.close(id)
                else -> session.focusAudio(id)
            }
            val open = session.openTiles
            val audible = session.audibleTile
            assertTrue(open.size <= 3)
            assertEquals(open.distinct(), open)
            assertEquals(open.isEmpty(), audible == null)
            assertTrue(audible == null || audible in open)
        }
    }
}

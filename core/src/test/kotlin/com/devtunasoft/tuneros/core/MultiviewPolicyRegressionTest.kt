package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic coverage for the MultiviewPolicy slice. Complements MultiviewSessionTest with boundary
 * tables, exhaustive short operation sequences, capacity-retry-after-cleanup and repeated session reuse.
 * All inputs are synthetic ids and fixed capacities; no provider data or device state is involved.
 */
class MultiviewPolicyRegressionTest {
    private val admitted = MultiviewSession.Admission.ADMITTED
    private val atCapacity = MultiviewSession.Admission.AT_CAPACITY

    @Test fun policyUsesTheMostRestrictiveBoundAtEveryBoundary() {
        fun allowed(decoders: Int, providers: Int, user: Int) =
            MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(decoders, providers, user))
        // A zero user limit keeps the single-playback fallback; only resource exhaustion removes it.
        assertEquals(0, allowed(-1, 4, 4))
        assertEquals(0, allowed(4, -1, 4))
        assertEquals(0, allowed(4, 0, 4))
        assertEquals(1, allowed(4, 4, 0))
        assertEquals(1, allowed(4, 4, 1))
        assertEquals(2, allowed(2, 3, 4))
        assertEquals(3, allowed(4, 3, 3))
        assertEquals(4, allowed(4, 4, 4))
        assertEquals(4, allowed(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun chooseAudioTileAcceptsOnlyExistingIndicesAndFailsStructurallyOtherwise() {
        for (active in 1..4) {
            for (index in 0 until active) assertEquals(index, MultiviewPolicy.chooseAudioTile(index, active))
            assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(active, active) }
            assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(-1, active) }
        }
        assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(0, 0) }
        assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(0, -1) }
        assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(0, 5) }
    }

    @Test fun capacityRefusalIsNonDestructiveAndRetriesAfterCleanup() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(2, 2))
        assertEquals(admitted, session.open("a"))
        assertEquals(admitted, session.open("b"))
        assertTrue(session.focusAudio("b"))
        assertEquals(atCapacity, session.open("c"))
        assertEquals(listOf("a", "b"), session.openTiles)
        assertEquals("b", session.audibleTile)

        assertTrue(session.close("a"))
        assertEquals(admitted, session.open("c"))
        assertEquals(listOf("b", "c"), session.openTiles)
        assertEquals("b", session.audibleTile)
        assertTrue(session.close("b"))
        assertEquals("c", session.audibleTile)
        assertTrue(session.close("c"))
        assertNull(session.audibleTile)
        assertEquals(emptyList<String>(), session.openTiles)
    }

    @Test fun closingTheAudibleTileAlwaysLeavesExactlyOneNewOwner() {
        for (size in 1..4) {
            val ids = (0 until size).map { "tile$it" }
            for (audible in 0 until size) {
                val session = MultiviewSession(MultiviewPolicy.Capacity(4, 4))
                ids.forEach { assertEquals(admitted, session.open(it)) }
                assertTrue(session.focusAudio(ids[audible]))
                assertEquals(ids[audible], session.audibleTile)

                assertTrue(session.close(ids[audible]))
                val remaining = ids - ids[audible]
                val expected = remaining.getOrNull(minOf(audible, remaining.lastIndex))
                assertEquals(expected, session.audibleTile)
                assertEquals(remaining, session.openTiles)
                assertEquals(if (size == 1) 0 else 1, session.openTiles.count { it == session.audibleTile })
            }
        }
    }

    @Test fun everyShortOperationSequenceKeepsCapacityAndAudioInvariants() {
        val ids = listOf("a", "b", "c", "d")
        val operations: List<(MultiviewSession, String) -> Unit> = listOf(
            { session, id -> session.open(id) },
            { session, id -> session.close(id) },
            { session, id -> session.focusAudio(id) },
        )
        val choices = ids.size * operations.size
        val sequences = choices * choices * choices * choices
        repeat(sequences) { code ->
            val session = MultiviewSession(MultiviewPolicy.Capacity(3, 4))
            var remaining = code
            repeat(4) {
                val choice = remaining % choices
                remaining /= choices
                operations[choice / ids.size](session, ids[choice % ids.size])

                val tiles = session.openTiles
                val audible = session.audibleTile
                assertTrue(tiles.size <= session.maxTiles)
                assertEquals(tiles.distinct(), tiles)
                assertEquals(tiles.isEmpty(), audible == null)
                assertTrue(audible == null || audible in tiles)
                assertEquals(if (audible == null) 0 else 1, tiles.count { it == audible })
            }
        }
    }

    @Test fun repeatedCreateReleaseCyclesRetainNoTilesAndNoCapacityDrift() {
        val capacity = MultiviewPolicy.Capacity(2, 2)
        val maxTiles = MultiviewPolicy.allowedTiles(capacity)
        repeat(500) {
            val session = MultiviewSession(capacity)
            assertEquals(maxTiles, session.maxTiles)
            assertEquals(admitted, session.open("tile-a"))
            assertEquals(admitted, session.open("tile-b"))
            assertEquals(atCapacity, session.open("tile-c"))
            assertTrue(session.focusAudio("tile-b"))
            assertEquals("tile-b", session.audibleTile)

            // Release the failing tile first, then the audible one: no tile may survive the cycle.
            assertTrue(session.close("tile-a"))
            assertTrue(session.close("tile-b"))
            assertEquals(emptyList<String>(), session.openTiles)
            assertNull(session.audibleTile)
            assertEquals(admitted, session.open("tile-c"))
            assertTrue(session.close("tile-c"))
            assertEquals(emptyList<String>(), session.openTiles)
            assertNull(session.audibleTile)
        }
    }
}

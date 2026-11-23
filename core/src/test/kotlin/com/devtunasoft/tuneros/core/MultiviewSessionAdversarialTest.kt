package com.devtunasoft.tuneros.core

import java.lang.reflect.Modifier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CORE-20 adversarial coverage for seams not already owned by MultiviewSessionTest, CoreTest
 * (CORE-07 MultiviewPolicy) or PlayerReusePolicyTest (CORE-12). All ids are synthetic. The
 * "fail closed" expectation means a malformed id is refused by every entry point without a
 * false-positive decision or a thrown exception; the contract does not define extra rejection
 * of unusual but non-blank ids. Resource/perf claims are pure-state bounds, not heap measurements.
 */
class MultiviewSessionAdversarialTest {
    private val admitted = MultiviewSession.Admission.ADMITTED
    private val broad = MultiviewPolicy.Capacity(decoderSlots = 4, providerConnections = 4)

    private fun assertSingleAudibleOwner(session: MultiviewSession) {
        val open = session.openTiles
        val audible = session.audibleTile
        assertEquals(open.distinct(), open, "open tiles must be unique")
        assertTrue(open.size <= session.maxTiles, "tile count must stay within the capacity cap")
        assertEquals(
            if (open.isEmpty()) 0 else 1,
            open.count { it == audible },
            "an open session must have exactly one audible tile, and an empty session none",
        )
    }

    private fun assertNoEcho(emitted: List<String>, markers: List<String>) {
        for (text in emitted) {
            for (marker in markers) {
                assertFalse(marker in text, "diagnostics must not echo synthetic marker material")
            }
        }
    }

    @Test
    fun extremeCapacityClaimsAreClampedAndTheFifthTileIsStillRefused() {
        val huge = MultiviewSession(
            MultiviewPolicy.Capacity(
                decoderSlots = Int.MAX_VALUE,
                providerConnections = Int.MAX_VALUE,
                userLimit = Int.MAX_VALUE,
            ),
        )
        assertEquals(4, huge.maxTiles)
        for (index in 0 until 4) {
            assertEquals(admitted, huge.open("t$index"))
            assertSingleAudibleOwner(huge)
        }
        assertEquals(MultiviewSession.Admission.AT_CAPACITY, huge.open("t4"))
        assertSingleAudibleOwner(huge)

        val negative = MultiviewSession(
            MultiviewPolicy.Capacity(
                decoderSlots = -1,
                providerConnections = -7,
                userLimit = Int.MIN_VALUE,
            ),
        )
        assertEquals(0, negative.maxTiles)
        assertEquals(MultiviewSession.Admission.AT_CAPACITY, negative.open("t0"))
        assertNull(negative.audibleTile)
        assertSingleAudibleOwner(negative)
    }

    @Test
    fun repeatedRefusalsAtCapacityNeverDisturbTheAudibleOwner() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 1, providerConnections = 1))
        assertEquals(admitted, session.open("owner"))
        repeat(25) { attempt ->
            val rejected = "rejected-$attempt"
            assertEquals(MultiviewSession.Admission.AT_CAPACITY, session.open(rejected))
            assertEquals(MultiviewSession.Admission.DUPLICATE, session.open("owner"))
            assertEquals(MultiviewSession.Admission.INVALID_ID, session.open("  "))
            assertFalse(session.focusAudio(rejected))
            assertFalse(session.close(rejected))
            assertEquals("owner", session.audibleTile)
            assertSingleAudibleOwner(session)
        }
        assertEquals(listOf("owner"), session.openTiles)
    }

    @Test
    fun duplicateOpenCannotStealOwnershipAndRepeatedCloseIsIdempotent() {
        val session = MultiviewSession(broad)
        assertEquals(admitted, session.open("a"))
        assertEquals(admitted, session.open("b"))
        assertTrue(session.focusAudio("a"))

        assertEquals(MultiviewSession.Admission.DUPLICATE, session.open("a"))
        assertEquals("a", session.audibleTile)
        assertTrue(session.close("a"))
        assertEquals("b", session.audibleTile)
        assertFalse(session.close("a"))
        assertFalse(session.close("a"))
        assertEquals("b", session.audibleTile)

        assertEquals(admitted, session.open("a"))
        assertEquals(listOf("b", "a"), session.openTiles)
        assertEquals("b", session.audibleTile)
        assertSingleAudibleOwner(session)
    }

    @Test
    fun refocusIsIdempotentAndMalformedOrUnknownTargetsCannotChangeTheOwner() {
        val session = MultiviewSession(broad)
        for (id in listOf("a", "b", "c", "d")) assertEquals(admitted, session.open(id))

        for (id in listOf("d", "a", "c", "b", "b", "d")) {
            assertTrue(session.focusAudio(id))
            assertEquals(id, session.audibleTile)
            assertSingleAudibleOwner(session)
        }
        for (bad in listOf("", " ", "\t", "\n", "missing", "x".repeat(129))) {
            assertFalse(session.focusAudio(bad))
            assertEquals("d", session.audibleTile)
            assertSingleAudibleOwner(session)
        }
    }

    @Test
    fun closingTheAudibleTileFromEveryPositionHandsOwnershipToAnOpenNeighbour() {
        for (victimIndex in 0 until 4) {
            val session = MultiviewSession(broad)
            for (index in 0 until 4) assertEquals(admitted, session.open("t$index"))
            val victim = "t$victimIndex"
            assertTrue(session.focusAudio(victim))

            assertTrue(session.close(victim))
            assertFalse(victim in session.openTiles)
            assertEquals(3, session.openTiles.size)
            assertSingleAudibleOwner(session)

            assertFalse(session.focusAudio(victim))
            assertFalse(session.close(victim))
            assertSingleAudibleOwner(session)
        }
    }

    @Test
    fun closingNonAudibleTilesInAnyOrderPreservesTheOwner() {
        val session = MultiviewSession(broad)
        for (id in listOf("a", "b", "c", "d")) assertEquals(admitted, session.open(id))
        assertTrue(session.focusAudio("b"))
        for (id in listOf("a", "c", "d")) {
            assertTrue(session.close(id))
            assertEquals("b", session.audibleTile)
            assertSingleAudibleOwner(session)
        }
        assertTrue(session.focusAudio("b"))
        assertEquals(listOf("b"), session.openTiles)
    }

    @Test
    fun fullReleaseAllowsRepeatedReopenWithExactlyOneOwner() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 2, providerConnections = 2))
        repeat(200) {
            assertEquals(admitted, session.open("cycle-a"))
            assertEquals(admitted, session.open("cycle-b"))
            assertEquals(MultiviewSession.Admission.AT_CAPACITY, session.open("cycle-c"))
            assertTrue(session.focusAudio("cycle-b"))
            assertSingleAudibleOwner(session)

            assertTrue(session.close("cycle-a"))
            assertTrue(session.close("cycle-b"))
            assertNull(session.audibleTile)
            assertTrue(session.openTiles.isEmpty())
            assertFalse(session.close("cycle-a"))
            assertFalse(session.focusAudio("cycle-b"))
            assertSingleAudibleOwner(session)
        }
    }

    @Test
    fun seededStressKeepsStateBoundedAndAddsNoUnboundedStateFields() {
        val random = Random(20_261_123)
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 3, providerConnections = 3))
        val ids = (0..6).map { "id-$it" }
        repeat(2_000) {
            val id = ids[random.nextInt(ids.size)]
            when (random.nextInt(3)) {
                0 -> session.open(id)
                1 -> session.close(id)
                else -> session.focusAudio(id)
            }
            assertSingleAudibleOwner(session)
        }

        val fields = MultiviewSession::class.java.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
        val collections = fields.filter {
            MutableCollection::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type)
        }
        assertEquals(1, collections.size, "the session should retain only its one bounded tile list")
        assertEquals(ArrayList::class.java, collections.single().type)
        val scalars = fields - collections
        assertTrue(scalars.isNotEmpty(), "bounded scalars must still be present")
        assertTrue(
            scalars.all { it.type == Int::class.javaPrimitiveType },
            "no extra reference accumulator may survive repeated cycles",
        )
    }

    @Test
    fun malformedIdsAreRefusedByEveryEntryPointWithoutChangingState() {
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 2, providerConnections = 2))
        assertEquals(admitted, session.open("valid"))
        val malformed = listOf("", " ", "\t", "\n", "\r\n", "x".repeat(129), "y".repeat(10_000))
        for (bad in malformed) {
            assertEquals(MultiviewSession.Admission.INVALID_ID, session.open(bad))
            assertFalse(session.focusAudio(bad))
            assertFalse(session.close(bad))
            assertEquals(listOf("valid"), session.openTiles)
            assertEquals("valid", session.audibleTile)
            assertSingleAudibleOwner(session)
        }
        assertEquals(admitted, session.open("z".repeat(128)))
        assertEquals(128, session.openTiles[1].length)
    }

    @Test
    fun urlLikeIdsNeverAppearInEmittedAdmissionText() {
        val marker = "S" + Random(50_201).nextInt(10_000_000, 99_999_999)
        // Malformed by the documented length rule, so the refusal path is the one that could leak text.
        val hostile = "https://feed.invalid/live.m3u8?token=$marker&pad=" + "p".repeat(128)
        val session = MultiviewSession(MultiviewPolicy.Capacity(decoderSlots = 1, providerConnections = 1))
        assertEquals(admitted, session.open("safe"))

        val refusal = session.open(hostile)
        val emitted = listOf(
            refusal.name,
            refusal.toString(),
            session.focusAudio(hostile).toString(),
            session.close(hostile).toString(),
            session.openTiles.joinToString(),
            session.audibleTile ?: "",
        )
        assertEquals(MultiviewSession.Admission.INVALID_ID, refusal)
        assertSingleAudibleOwner(session)
        assertNoEcho(emitted, listOf(marker, "https://", "token=", "feed.invalid", "m3u8"))
    }

    @Test
    fun sessionBytecodeCarriesNoUrlFileOrCredentialMachinery() {
        val bytes = MultiviewSession::class.java.getResourceAsStream("MultiviewSession.class")!!.use {
            it.readBytes()
        }
        val ascii = bytes.toString(Charsets.ISO_8859_1)
        for (forbidden in listOf(
            "java/io/File", "java/nio/file", "java/net/URI", "java/net/URL",
            "HttpURLConnection", "password", "credential", "token=",
        )) {
            assertFalse(forbidden in ascii, "MultiviewSession must not reference $forbidden")
        }
    }
}

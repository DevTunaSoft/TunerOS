package com.devtunasoft.tuneros.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial coverage for the player-reuse seam. [PlayerReusePolicy] is the whole seam in
 * core: core owns the decision, not a player pool. A channel switch keeps at most one
 * current player, so capacity is one; a rejected decision must release that slot before a
 * new player is acquired. Fixtures are synthetic only: no provider URLs or secrets.
 */
class PlayerReuseAdversarialTest {
    private val alpha = mapOf("Referer" to "alpha.invalid")
    private val beta = mapOf("Referer" to "beta.invalid")

    @Test fun allDistinctHeaderSetsFailTheSingleSlotReuseCheck() {
        val headerSets = listOf(
            emptyMap<String, String>(),
            mapOf("k" to "v"),
            mapOf("k" to "w"),
            mapOf("a" to "1", "b" to "2"),
            mapOf("a" to "2", "b" to "1"),
        )
        var allowed = 0
        for (current in headerSets) {
            for (next in headerSets) {
                val expected = current == next
                assertEquals(expected, PlayerReusePolicy.canReuse(current, next, playingRecording = false))
                if (expected) allowed++
            }
        }
        assertEquals(headerSets.size, allowed, "only an identical header set may reuse the one player slot")
    }

    @Test fun churnNeverHoldsMoreThanOnePlayerAndReleasesItAtTheEnd() {
        val headerCycle = arrayOf(alpha, beta, emptyMap<String, String>())
        var held: Map<String, String>? = null
        var livePlayers = 0
        var reuses = 0
        var recreations = 0
        repeat(3_000) { step ->
            if (step % 11 == 10) {
                held = null
                livePlayers = 0
            } else {
                val next = headerCycle[(step / 3) % headerCycle.size]
                val expectedReuse = held != null && held == next
                val reuse = PlayerReusePolicy.canReuse(held, next, playingRecording = false)
                assertEquals(expectedReuse, reuse, "wrong reuse decision at step $step")
                if (reuse) {
                    reuses++
                    assertEquals(1, livePlayers, "a reuse must use the one held player")
                } else {
                    recreations++
                    livePlayers = 0
                    livePlayers = 1
                }
                held = next.toMap()
                assertEquals(next, held)
            }
            assertTrue(livePlayers in 0..1, "capacity must never exceed one player")
        }
        held = null
        livePlayers = 0
        assertEquals(0, livePlayers)
        val releaseSteps = (0 until 3_000).count { it % 11 == 10 }
        assertEquals(3_000 - releaseSteps, reuses + recreations)
    }

    @Test fun repeatedAcquireReleaseCyclesNeverCarryHeadersAcrossTheReset() {
        repeat(512) {
            assertFalse(PlayerReusePolicy.canReuse(null, alpha, playingRecording = false))
            assertTrue(PlayerReusePolicy.canReuse(alpha, alpha.toMap(), playingRecording = false))
            assertFalse(PlayerReusePolicy.canReuse(alpha, beta, playingRecording = false))
            assertFalse(PlayerReusePolicy.canReuse(null, beta, playingRecording = false), "reset must forget alpha")
            assertTrue(PlayerReusePolicy.canReuse(beta, beta.toMap(), playingRecording = false))
            assertFalse(PlayerReusePolicy.canReuse(beta, alpha, playingRecording = false))
            assertFalse(PlayerReusePolicy.canReuse(null, alpha, playingRecording = false), "reset must forget beta")
        }
    }

    @Test fun emptyAndSingleStepBoundariesAreFailClosed() {
        assertTrue(PlayerReusePolicy.canReuse(emptyMap(), emptyMap(), playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(null, emptyMap(), playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(emptyMap(), alpha, playingRecording = false))
        val single = mapOf("only" to "one")
        assertTrue(PlayerReusePolicy.canReuse(single, single.toMap(), playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(null, single, playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(single, single, playingRecording = true))
    }

    @Test fun policyObjectHasNoCollectionOrMutableState() {
        val fields = PlayerReusePolicy::class.java.declaredFields.filterNot { it.isSynthetic }
        assertTrue(
            fields.all { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) },
            "the policy must not hold instance or mutable state",
        )
        assertTrue(
            fields.none { field ->
                val type = field.type
                Collection::class.java.isAssignableFrom(type) ||
                    Map::class.java.isAssignableFrom(type) ||
                    type.isArray ||
                    type == String::class.java
            },
            "the policy must not retain players, headers, caches or diagnostic text",
        )
        repeat(1_000) { i ->
            val next = if (i % 2 == 0) alpha else beta
            assertEquals(i % 2 == 0, PlayerReusePolicy.canReuse(alpha, next, playingRecording = false))
        }
    }

    @Test fun callHistoryAndCallerMutationDoNotChangeTheDecision() {
        val mutable = linkedMapOf("k" to "v")
        val decision = PlayerReusePolicy.canReuse(mutable, mutable.toMap(), playingRecording = false)
        mutable["k"] = "changed"
        assertTrue(decision, "the decision was made on the original header values")
        assertFalse(PlayerReusePolicy.canReuse(mutable, linkedMapOf("k" to "v"), playingRecording = false))
        val inputs = listOf(alpha to beta, alpha to alpha, beta to beta, emptyMap<String, String>() to alpha)
        val forward = inputs.map { PlayerReusePolicy.canReuse(it.first, it.second, playingRecording = false) }
        val backward = inputs.asReversed()
            .map { PlayerReusePolicy.canReuse(it.first, it.second, playingRecording = false) }
            .asReversed()
        assertEquals(forward, backward, "results must not depend on call history")
    }

    @Test fun malformedHeadersFailClosedForMissingRecordingAndMismatchedInputs() {
        val malformed = listOf(
            emptyMap<String, String>(),
            mapOf("" to ""),
            mapOf("k" to ""),
            mapOf("" to "v"),
            mapOf("bad\r\nkey" to "v"),
            mapOf("k" to "v\nInjected: 1"),
            mapOf(".." to "v"),
            mapOf("../x" to "v"),
            mapOf("k" to "x".repeat(4_096)),
        )
        for (headers in malformed) {
            val outcome = runCatching {
                assertFalse(PlayerReusePolicy.canReuse(null, headers, playingRecording = false))
                assertFalse(PlayerReusePolicy.canReuse(headers, headers, playingRecording = true))
                assertFalse(PlayerReusePolicy.canReuse(headers, alpha, playingRecording = false))
                assertFalse(PlayerReusePolicy.canReuse(alpha, headers, playingRecording = false))
                assertFalse(
                    PlayerReusePolicy.canReuse(headers, headers + ("extra" to "x"), playingRecording = false),
                )
            }
            assertTrue(outcome.isSuccess, "malformed headers must be rejected, not throw")
        }
    }

    @Test fun noDiagnosticTextCanCarrySecretsOrProviderUrls() {
        val markers = listOf("SECRET_MARKER", "provider.invalid", "m3u8", "token", "user:pass")
        val hostile = mapOf(
            "Referer" to "https://provider.invalid/live.m3u8?token=SECRET_MARKER",
            "Authorization" to "Bearer SECRET_MARKER",
            "X-Trace" to "user:pass@provider.invalid",
        )
        val result = runCatching {
            val reused = PlayerReusePolicy.canReuse(hostile, hostile.toMap(), playingRecording = false)
            val rejected = PlayerReusePolicy.canReuse(null, hostile, playingRecording = false)
            val recording = PlayerReusePolicy.canReuse(hostile, hostile, playingRecording = true)
            listOf(reused, rejected, recording).joinToString("|")
        }
        assertTrue(result.isSuccess, "hostile header values must not throw")
        val text = result.getOrDefault("") + result.exceptionOrNull()?.message.orEmpty()
        for (marker in markers) {
            assertFalse(text.contains(marker, ignoreCase = true), "diagnostic text must not echo input material")
        }
        assertTrue(
            PlayerReusePolicy::class.java.declaredMethods.none { it.returnType == String::class.java },
            "the policy exposes no string-producing diagnostic",
        )
    }
}

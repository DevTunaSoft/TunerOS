package com.devtunasoft.tuneros.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CORE-28 adversarial JVM coverage for the guide-filtering and player-reuse policy seam.
 *
 * Audit boundary: ChannelVisibilityTest and FavoriteChannelsTest already cover ordinary search,
 * hide-no-EPG with a future entry, toggle order and stable-key preference; PlayerReusePolicyTest covers
 * the basic reuse truth table. Sibling branches carry ChannelVisibilityBoundedSetTest,
 * FavoriteChannelsBoundedSetTest and PlayerReuseAdversarialTest (large duplicate lists, 25k favorite
 * sets, churn and structural state checks). This file adds only cases those files do not exercise:
 * expired-only schedules, the parser cap with duplicate keys, blank guide IDs against malformed guide
 * input, duplicate playlist IDs sharing a stable key, id fallback, over-cap sets, exact header identity
 * and the absence of filesystem/network machinery in the three pure policy classes.
 *
 * All names, identifiers and addresses are synthetic scratch fixtures. Marker strings are assembled at
 * runtime so no credential-shaped literal enters this test class constant pool.
 */
class Core28AdversarialCoverageTest {
    private val cap = 25_000

    private fun scratchUrl(index: Int) = "https://example.invalid/scratch/$index.ts"

    private fun marker(): String = String(
        charArrayOf('C', 'O', 'R', 'E', '2', '8', 'S', 'C', 'R', 'A', 'T', 'C', 'H', 'M', 'A', 'R', 'K'),
    )

    private fun classBytes(type: Class<*>): String {
        val resource = type.getResourceAsStream("${type.simpleName}.class")
            ?: throw AssertionError("compiled class not visible on test classpath")
        return resource.use { it.readBytes().toString(Charsets.ISO_8859_1) }
    }

    private fun assertNoAddressingMachinery(type: Class<*>) {
        val decoded = classBytes(type)
        for (forbidden in listOf("java/io/File", "java/nio/file", "java/net/URI", "java/net/URL")) {
            assertFalse(decoded.contains(forbidden), "${type.simpleName} must not carry $forbidden")
        }
        assertFalse(decoded.contains(marker(), true))
    }

    private fun assertNoInstanceOrStringState(type: Class<*>) {
        val fields = type.declaredFields.filterNot { it.isSynthetic }
        assertTrue(
            fields.all { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) },
            "${type.simpleName} must not hold instance or mutable state",
        )
        assertTrue(
            type.declaredMethods.none { it.returnType == String::class.java },
            "${type.simpleName} must not expose a string diagnostic",
        )
    }

    @Test
    fun expiredOnlyScheduleStillCountsAsEpgWhenHideNoEpgIsOn() {
        val channel = Channel(
            "expired-only", "Scratch News", scratchUrl(1), guideId = "guide-expired",
        )
        val guide = GuideSnapshot(
            listOf(GuideProgram("guide-expired", "Long finished", 1_000L, 2_000L)), emptyMap(),
        )
        guide.prepareIndex()
        assertNull(guide.now("guide-expired", 9_000L), "nothing is playing now")
        assertEquals(
            listOf("expired-only"),
            ChannelVisibility.filter(listOf(channel), guide, "", true).map { it.id },
        )
    }

    @Test
    fun parserCapCatalogWithDuplicateIdsKeepsEveryGuideMatchInPlaylistOrder() {
        val channels = List(cap) { index ->
            Channel(
                "duplicate-${index % 7}", "Scratch ${index % 3}", scratchUrl(index),
                guideId = if (index % 5 == 0) "guide-shared" else "guide-other-$index",
                stableKey = "key-$index",
            )
        }
        val guide = GuideSnapshot(
            listOf(GuideProgram("guide-shared", "Future", 20_000L, 21_000L)), emptyMap(),
        )
        guide.prepareIndex()
        val hidden = ChannelVisibility.filter(channels, guide, "", true)
        assertEquals(5_000, hidden.size)
        for ((position, channel) in hidden.withIndex()) {
            assertSame(channels[position * 5], channel)
        }
        val searched = ChannelVisibility.filter(channels, guide, "SCRATCH 1", true)
        assertEquals(hidden.filter { it.name.contains("Scratch 1", ignoreCase = true) }, searched)
        assertSame(channels, ChannelVisibility.filter(channels, null, "", false))
    }

    @Test
    fun blankGuideIdsDoNotMatchMalformedBlankScheduleEntries() {
        val channels = listOf(
            Channel("blank-id", "Scratch", scratchUrl(2), guideId = ""),
            Channel("spaces-id", "Scratch", scratchUrl(3), guideId = "   "),
            Channel("no-id", "Scratch", scratchUrl(4), guideId = null),
            Channel("good-id", "Scratch", scratchUrl(5), guideId = "guide-good"),
        )
        val guide = GuideSnapshot(
            listOf(
                GuideProgram("guide-good", "Future", 20_000L, 21_000L),
                GuideProgram("", "Malformed blank channel", 20_000L, 21_000L),
            ),
            emptyMap(),
        )
        guide.prepareIndex()
        assertEquals(
            listOf("good-id"),
            ChannelVisibility.filter(channels, guide, "", true).map { it.id },
        )
        assertEquals(
            listOf("good-id"),
            ChannelVisibility.filter(channels, guide, " scratch ", true).map { it.id },
        )
        assertEquals(4, ChannelVisibility.filter(channels, guide, "", false).size)
    }

    @Test
    fun visibilitySeamHasNoStringDiagnosticsOrAddressingMachinery() {
        assertNoInstanceOrStringState(ChannelVisibility::class.java)
        assertNoAddressingMachinery(ChannelVisibility::class.java)
        val scheme = String(charArrayOf('h', 't', 't', 'p', 's'))
        val authority = "scratch.invalid"
        val hostileName = "Scratch $scheme://$authority/live?token=unrelated"
        val channel = Channel("opaque-id", hostileName, scratchUrl(6))
        val outcome = runCatching {
            ChannelVisibility.filter(listOf(channel), null, marker(), false)
        }
        assertTrue(outcome.isSuccess, "a marker-bearing query must not throw")
        assertTrue(outcome.getOrDefault(emptyList()).isEmpty())
        assertFalse(outcome.exceptionOrNull()?.message.orEmpty().contains(marker(), true))
    }

    @Test
    fun duplicatePlaylistIdsSharingAStableKeyAreAllReturnedInOrder() {
        val channels = listOf(
            Channel("same-id", "Scratch A", scratchUrl(7), stableKey = "shared-key"),
            Channel("same-id", "Scratch B", scratchUrl(8), stableKey = "shared-key"),
            Channel("other-id", "Scratch C", scratchUrl(9), stableKey = "other-key"),
        )
        val result = FavoriteChannels.filter(channels, setOf("shared-key"), true)
        assertEquals(listOf("same-id", "same-id"), result.map { it.id })
        assertSame(channels[0], result[0])
        assertSame(channels[1], result[1])
    }

    @Test
    fun blankIdsNeverBecomeFavoritesAndIdFallbackStillMatches() {
        val before = setOf("kept-key")
        assertSame(before, FavoriteChannels.toggle(before, ""))
        assertSame(before, FavoriteChannels.toggle(before, " \t\n"))
        val channels = listOf(
            Channel("legacy-id", "Scratch Legacy", scratchUrl(10)),
            Channel("", "Scratch Blank", scratchUrl(11)),
        )
        assertEquals(
            listOf("legacy-id"),
            FavoriteChannels.filter(channels, setOf("legacy-id"), true).map { it.id },
        )
        assertTrue(FavoriteChannels.filter(channels, emptySet(), true).isEmpty())
    }

    @Test
    fun overCapFavoriteSetAndCatalogDoNotTruncateOrAliasTheCaller() {
        val favorites = (0..cap).mapTo(HashSet()) { "k-" + it.toString().padStart(6, '0') }
        val callerSnapshot = HashSet(favorites)
        val added = FavoriteChannels.toggle(favorites, "k-extra")
        assertEquals(cap + 2, added.size)
        assertTrue(added.containsAll(callerSnapshot) && "k-extra" in added)
        assertEquals(cap + 1, favorites.size)
        val removed = FavoriteChannels.toggle(added, "k-000000")
        assertEquals(cap + 1, removed.size)
        assertFalse("k-000000" in removed)
        val channels = List(cap + 1) { index ->
            Channel(
                "id-$index", "Scratch $index", scratchUrl(index),
                stableKey = "k-" + index.toString().padStart(6, '0'),
            )
        }
        val filtered = FavoriteChannels.filter(channels, removed, true)
        assertEquals(cap, filtered.size)
        assertEquals("id-1", filtered.first().id)
        assertEquals("id-$cap", filtered.last().id)
    }

    @Test
    fun favoritesSeamHasNoStringDiagnosticsOrAddressingMachinery() {
        assertNoInstanceOrStringState(FavoriteChannels::class.java)
        assertNoAddressingMachinery(FavoriteChannels::class.java)
        val hostileKey = "scratch://${marker()}/live"
        val outcome = runCatching {
            val toggled = FavoriteChannels.toggle(emptySet(), hostileKey)
            FavoriteChannels.filter(emptyList(), toggled, true).isEmpty()
        }
        assertTrue(outcome.isSuccess, "an opaque marker-bearing key must not throw")
        assertTrue(outcome.getOrDefault(false))
        assertFalse(outcome.exceptionOrNull()?.message.orEmpty().contains(marker(), true))
    }

    @Test
    fun recordingRefusalIsTransientAndNeverCrossesAProviderHeaderChange() {
        val first = mapOf("Referer" to "first.invalid")
        val second = mapOf("Referer" to "second.invalid")
        assertFalse(PlayerReusePolicy.canReuse(null, first, playingRecording = false))
        var current: Map<String, String>? = first.toMap()
        assertTrue(PlayerReusePolicy.canReuse(current, first, playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(current, first, playingRecording = true))
        current = null
        assertFalse(PlayerReusePolicy.canReuse(current, first, playingRecording = false))
        current = first.toMap()
        assertTrue(PlayerReusePolicy.canReuse(current, first, playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(current, second, playingRecording = false))
        current = second.toMap()
        assertTrue(PlayerReusePolicy.canReuse(current, second, playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(current, first, playingRecording = false))
    }

    @Test
    fun oversizedHeaderMapsCompareExactlyAndDoNotThrow() {
        val base = (0 until 4_096).associate { "h-$it" to "v-$it" }
        val reversedOrder = (4_095 downTo 0).associate { "h-$it" to "v-$it" }
        assertTrue(PlayerReusePolicy.canReuse(base, reversedOrder, playingRecording = false))
        val changed = LinkedHashMap(reversedOrder)
        changed["h-4095"] = "different"
        assertFalse(PlayerReusePolicy.canReuse(base, changed, playingRecording = false))
        val hostile = base + ("Authorization" to "Bearer ${marker()}")
        val outcome = runCatching {
            assertTrue(PlayerReusePolicy.canReuse(hostile, hostile.toMap(), playingRecording = false))
            assertFalse(PlayerReusePolicy.canReuse(hostile, base, playingRecording = false))
        }
        assertTrue(outcome.isSuccess, "oversized marker-bearing headers must not throw")
        assertFalse(outcome.exceptionOrNull()?.message.orEmpty().contains(marker(), true))
    }

    @Test
    fun headerIdentityIsExactAndCaseSensitive() {
        val lower = mapOf("referer" to "value")
        val upperKey = mapOf("Referer" to "value")
        val upperValue = mapOf("referer" to "VALUE")
        assertTrue(PlayerReusePolicy.canReuse(lower, lower.toMap(), playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(lower, upperKey, playingRecording = false))
        assertFalse(PlayerReusePolicy.canReuse(lower, upperValue, playingRecording = false))
    }

    @Test
    fun reusePolicyClassCarriesNoUrlMachineryOrSecretLiterals() {
        assertNoInstanceOrStringState(PlayerReusePolicy::class.java)
        assertNoAddressingMachinery(PlayerReusePolicy::class.java)
    }
}

package com.devtunasoft.tuneros.map05

import com.devtunasoft.tuneros.GuideMappingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAP-05 regressions: stale guide-mapping keys must be pruned caller-side once a live playlist is known.
 *
 * All fixtures are synthetic. No Android framework, no org.json, no provider data and no I/O: the helper is a
 * bounded map-to-map decision, so it is exercised deterministically by the plain AGP unit-test runner.
 */
class GuideMappingRetainTest {

    private fun retain(mappings: Map<String, String>, liveKeys: Set<String>) =
        GuideMappingStore.retain(mappings, liveKeys)

    @Test
    fun retainKeepsLiveMappingsAndDropsOnlyStaleKeys() {
        val mappings = linkedMapOf(
            "news.1" to "guide.news",
            "sports.1" to "guide.sports",
            "weather.1" to "guide.weather",
        )
        val retained = retain(mappings, setOf("news.1", "sports.1"))
        assertEquals(
            linkedMapOf("news.1" to "guide.news", "sports.1" to "guide.sports"),
            retained,
        )
    }

    @Test
    fun retainPreservesValuesAndInsertionOrderVerbatim() {
        // On-disk format is a JSON object written in iteration order; pruning must not rewrite values or order.
        val mappings = linkedMapOf(
            "chan.z" to "guide.z",
            "chan.empty" to "",
            "chan.unicode" to "guide.\u00e9\u6f22",
        )
        val retained = retain(mappings, setOf("chan.z", "chan.empty", "chan.unicode"))
        assertEquals(mappings, retained)
        assertEquals(mappings.keys.toList(), retained.keys.toList())
    }

    @Test
    fun retainIsIdempotentAcrossRepeatedPlays() {
        val mappings = linkedMapOf("live.a" to "guide.a", "stale.b" to "guide.b")
        val first = retain(mappings, setOf("live.a"))
        val second = retain(first, setOf("live.a"))
        assertEquals(first, second)
        assertSame(first, second)
    }

    @Test
    fun retainWithEmptyLivePlaylistKeepsEveryMapping() {
        // Fail closed: an empty or failed import must never be treated as "all keys are stale".
        val mappings = linkedMapOf("a" to "ga", "b" to "gb", "c" to "gc")
        val retained = retain(mappings, emptySet())
        assertEquals(mappings, retained)
        assertSame(mappings, retained)
    }

    @Test
    fun retainWithNoStaleKeysReturnsOriginalMapWithoutAllocating() {
        val mappings = linkedMapOf("a" to "ga", "b" to "gb")
        assertSame(mappings, retain(mappings, setOf("a", "b", "extra.live.key")))
    }

    @Test
    fun retainDoesNotMutateTheCallerMap() {
        val mappings = linkedMapOf("a" to "ga", "b" to "gb")
        val original = LinkedHashMap(mappings)
        retain(mappings, setOf("a"))
        assertEquals(original, mappings)
    }

    @Test
    fun retainHandlesEmptyMappings() {
        val mappings = emptyMap<String, String>()
        assertSame(mappings, retain(mappings, setOf("a")))
        assertSame(mappings, retain(mappings, emptySet()))
    }

    @Test
    fun retainAfterChannelRenumberingDoesNotAccumulateStaleKeys() {
        // Playlist v1 keys a,b,c; v2 renumbers c away; v3 brings a different c back.
        // The old c mapping must be gone after v2 and must never be resurrected by v3.
        val original = linkedMapOf("a" to "ga", "b" to "gb", "c.old" to "gc")
        val v2 = retain(original, setOf("a", "b"))
        assertEquals(linkedMapOf("a" to "ga", "b" to "gb"), v2)
        val v3 = retain(v2, setOf("a", "b", "c.new"))
        assertEquals(linkedMapOf("a" to "ga", "b" to "gb"), v3)
        assertTrue(v3.size <= 2)
    }

    @Test
    fun retainOverSyntheticLargePlaylistDropsExactlyTheStaleEntries() {
        // Bounded work and memory: one pass over the (small) saved-mapping map, independent of playlist size.
        val liveKeys = (0 until 2_000).mapTo(HashSet()) { "playlist.channel.$it" }
        val mappings = linkedMapOf(
            "playlist.channel.0" to "guide.0",
            "renumbered.channel.17" to "guide.17",
            "playlist.channel.1999" to "guide.1999",
        )
        assertEquals(
            linkedMapOf(
                "playlist.channel.0" to "guide.0",
                "playlist.channel.1999" to "guide.1999",
            ),
            retain(mappings, liveKeys),
        )
    }

    @Test
    fun retainDropsAllMappingsWhenNoneOfTheKeysAreLive() {
        val mappings = linkedMapOf("gone.1" to "g1", "gone.2" to "g2")
        assertEquals(emptyMap<String, String>(), retain(mappings, setOf("live.1")))
    }
}

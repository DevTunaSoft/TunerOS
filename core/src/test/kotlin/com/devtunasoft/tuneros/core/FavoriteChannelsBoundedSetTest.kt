package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Bounded-set coverage for [FavoriteChannels]. The persisted set belongs to the caller: toggling neither imposes a
 * silent cap nor keeps a reference to it. All identifiers and URLs below are scratch fixtures. There is no diagnostics
 * sink in this API, so the privacy invariant is checked on the only derived text it produces: the favorite key.
 */
class FavoriteChannelsBoundedSetTest {
    private fun scratchChannels(count: Int) = List(count) { index ->
        Channel(
            "id-$index", "Scratch $index", "https://example.invalid/scratch/$index.ts",
            stableKey = "k" + index.toString().padStart(6, '0'),
        )
    }

    @Test fun largeFavoriteSetAddsAndRemovesWithoutDroppingOtherKeys() {
        val favorites = (0 until 25_000).mapTo(HashSet()) { "k" + it.toString().padStart(6, '0') }
        val added = FavoriteChannels.toggle(favorites, "k-extra")
        assertEquals(25_001, added.size)
        assertTrue("k-extra" in added)
        assertTrue(favorites.all { it in added })
        val removed = FavoriteChannels.toggle(added, "k000001")
        assertEquals(25_000, removed.size)
        assertFalse("k000001" in removed)
        assertTrue("k000000" in removed && "k024999" in removed && "k-extra" in removed)
    }

    @Test fun repeatedDuplicateTogglesNeverGrowTheSet() {
        var favorites: Set<String> = emptySet()
        repeat(201) { favorites = FavoriteChannels.toggle(favorites, "k-duplicate") }
        assertEquals(setOf("k-duplicate"), favorites)
        repeat(201) { favorites = FavoriteChannels.toggle(favorites, "k-duplicate") }
        assertTrue(favorites.isEmpty(), "402 toggles from empty must return to empty")
    }

    @Test fun emptyCollectionInputsStayEmptyAndMalformedIdsAreIgnored() {
        assertTrue(FavoriteChannels.filter(emptyList(), setOf("k-a"), favoritesOnly = true).isEmpty())
        assertTrue(FavoriteChannels.filter(scratchChannels(8), emptySet(), favoritesOnly = true).isEmpty())
        val callerSet = HashSet<String>()
        assertSame(callerSet, FavoriteChannels.toggle(callerSet, "\t\n"))
        assertEquals(0, callerSet.size)
    }

    @Test fun toggleDoesNotMutateOrAliasTheCallerSet() {
        val source = LinkedHashSet(listOf("k-a", "k-b"))
        val afterAdd = FavoriteChannels.toggle(source, "k-c")
        assertEquals(setOf("k-a", "k-b"), source)
        assertEquals(setOf("k-a", "k-b", "k-c"), afterAdd)
        source.add("k-late")
        assertEquals(setOf("k-a", "k-b", "k-c"), afterAdd)
        repeat(500) { index ->
            val input = setOf("k-$index")
            assertEquals(setOf("k-$index", "k-next"), FavoriteChannels.toggle(input, "k-next"))
        }
    }

    @Test fun persistedFavoriteKeyNeverContainsProviderUrlOrTokenText() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="scratch-news",Scratch News
            https://example.invalid/live/scratch.m3u8?token=SCRATCH_TOKEN_VALUE
        """.trimIndent()
        val channel = PlaylistParser.parseReport(playlist.reader()).channels.single()
        val favorites = FavoriteChannels.toggle(emptySet(), channel.storageKey)
        val key = favorites.single()
        assertTrue(key.matches(Regex("[0-9a-f]{24}")), "opaque key was $key")
        assertFalse(key.contains("SCRATCH_TOKEN_VALUE", ignoreCase = true))
        assertFalse(key.contains("example.invalid", ignoreCase = true))
        assertEquals(listOf(channel.id), FavoriteChannels.filter(listOf(channel), favorites, true).map { it.id })
    }
}

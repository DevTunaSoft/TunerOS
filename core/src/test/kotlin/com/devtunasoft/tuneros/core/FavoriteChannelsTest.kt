package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FavoriteChannelsTest {
    private val channels = listOf(
        Channel("a", "Alpha", "https://example.org/a"),
        Channel("b", "Beta", "https://example.org/b"),
        Channel("c", "Gamma", "https://example.org/c"),
    )

    @Test fun toggleAddsThenRemovesAnId() {
        val added = FavoriteChannels.toggle(emptySet<String>(), "b")
        assertEquals(setOf("b"), added)
        assertEquals(emptySet<String>(), FavoriteChannels.toggle(added, "b"))
    }

    @Test fun toggleKeepsOtherFavorites() {
        assertEquals(setOf("a", "b", "c"), FavoriteChannels.toggle(setOf("a", "c"), "b"))
    }

    @Test fun blankIdChangesNothing() {
        val before = setOf("a")
        assertSame(before, FavoriteChannels.toggle(before, "  "))
    }

    @Test fun favoritesOnlyKeepsPlaylistOrderAndIgnoresUnknownIds() {
        val result = FavoriteChannels.filter(channels, setOf("c", "a", "missing"), favoritesOnly = true)
        assertEquals(listOf("a", "c"), result.map { it.id })
    }

    @Test fun offReturnsTheSameListInstance() {
        assertSame(channels, FavoriteChannels.filter(channels, setOf("a"), favoritesOnly = false))
    }

    @Test fun favoritesOnlyWithoutFavoritesIsEmpty() {
        assertTrue(FavoriteChannels.filter(channels, emptySet<String>(), favoritesOnly = true).isEmpty())
    }

    @Test fun favoritesFollowTheStableKeyNotTheChannelId() {
        val rotated = Channel("new-url-id", "Alpha", "https://example.org/a?token=new", stableKey = "k-alpha")
        val result = FavoriteChannels.filter(listOf(rotated), setOf("k-alpha"), favoritesOnly = true)
        assertEquals(listOf("new-url-id"), result.map { it.id })
    }
}

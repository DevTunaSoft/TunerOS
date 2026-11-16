package com.devtunasoft.tuneros.core

/**
 * Channel favorites. Favorites are keyed by the stable channel ID, so a favorite survives a refresh of the same
 * playlist source. A favorite whose channel is no longer in the playlist is simply not shown.
 */
object FavoriteChannels {
    /** Adds [channelId] when it is absent and removes it when it is present. A blank ID changes nothing. */
    fun toggle(favorites: Set<String>, channelId: String): Set<String> {
        if (channelId.isBlank()) return favorites
        return if (channelId in favorites) favorites - channelId else favorites + channelId
    }

    /**
     * Returns [channels] itself when [favoritesOnly] is false. Otherwise returns only the favorites, in the
     * order they appear in [channels].
     */
    fun filter(channels: List<Channel>, favorites: Set<String>, favoritesOnly: Boolean): List<Channel> =
        if (!favoritesOnly) channels else channels.filter { it.storageKey in favorites }
}

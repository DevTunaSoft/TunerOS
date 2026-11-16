package com.devtunasoft.tuneros

import android.content.Context

/**
 * Favorite channel IDs in a preference set. The IDs are hashes derived from the source, not URLs or secrets, so
 * plain preferences are enough. A save commits synchronously: the set is small and a long press is a deliberate
 * user action.
 */
internal class FavoriteChannelStore(context: Context, prefsName: String = PREFS_NAME) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    fun save(ids: Set<String>): Boolean = prefs.edit().putStringSet(KEY, HashSet(ids)).commit()

    fun clear(): Boolean = prefs.edit().remove(KEY).commit()

    companion object {
        const val PREFS_NAME = "tuneros_alpha"
        private const val KEY = "favorite_channel_ids"
    }
}

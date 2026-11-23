package com.devtunasoft.tuneros

import android.content.Context
import org.json.JSONObject

/**
 * Guide Doctor mappings: channel ID to the guide channel ID the user chose. Stored as one JSON object in
 * preferences. Both IDs are source hashes or XMLTV channel IDs, not secrets, so plain preferences are enough.
 */
internal class GuideMappingStore(context: Context, prefsName: String = PREFS_NAME) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Corrupt or missing data reads as no mappings rather than failing the guide. */
    fun load(): Map<String, String> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching<Map<String, String>> {
            val json = JSONObject(raw)
            val result = HashMap<String, String>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val channelId = keys.next()
                result[channelId] = json.getString(channelId)
            }
            result
        }.getOrElse { emptyMap() }
    }

    /** Commits synchronously. A mapping changes on a deliberate user action and the object is small. */
    fun save(mappings: Map<String, String>): Boolean {
        val json = JSONObject()
        for ((channelId, guideId) in mappings) json.put(channelId, guideId)
        return prefs.edit().putString(KEY, json.toString()).commit()
    }

    fun clear(): Boolean = prefs.edit().remove(KEY).commit()

    companion object {
        const val PREFS_NAME = "tuneros_alpha"
        private const val KEY = "guide_mappings"

        /**
         * MAP-05 caller-side pruning. The store persists whatever it is given; only the caller knows the live
         * playlist, so it supplies [validKeys] (channel [storageKey][com.devtunasoft.tuneros.core.Channel.storageKey]s).
         *
         * Returns a map with only keys still live. An empty [validKeys] means "no confirmed playlist / import failed",
         * so the input is returned unchanged (fail closed). The input is also returned unchanged when nothing is stale,
         * making repeated calls idempotent and allocation-free on the common path. Otherwise exactly one small map,
         * bounded by the existing saved-mapping count, is allocated; values and their iteration order are preserved so
         * the on-disk JSON object is unchanged apart from the removed keys. The caller decides whether and when to save.
         */
        fun retain(mappings: Map<String, String>, validKeys: Set<String>): Map<String, String> {
            if (validKeys.isEmpty() || mappings.isEmpty()) return mappings
            var hasStaleKey = false
            for (key in mappings.keys) {
                if (key !in validKeys) {
                    hasStaleKey = true
                    break
                }
            }
            if (!hasStaleKey) return mappings
            val retained = LinkedHashMap<String, String>(mappings.size)
            for ((channelId, guideId) in mappings) {
                if (channelId in validKeys) retained[channelId] = guideId
            }
            return retained
        }
    }
}

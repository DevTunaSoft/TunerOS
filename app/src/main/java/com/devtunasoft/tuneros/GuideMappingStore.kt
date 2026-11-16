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
    }
}

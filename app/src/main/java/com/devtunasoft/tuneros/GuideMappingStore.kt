package com.devtunasoft.tuneros

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Guide Doctor mappings: channel ID to the guide channel ID the user chose. Stored as one JSON object in
 * preferences. Both IDs are source hashes or XMLTV channel IDs, not secrets, so plain preferences are enough.
 *
 * [Storage] and [Codec] are internal seams so the persistence rules can be exercised by plain JVM unit tests;
 * Android preferences and org.json are unavailable to local unit tests. The default constructor keeps the
 * production behaviour and on-disk format unchanged.
 */
internal class GuideMappingStore(
    private val storage: Storage,
    private val codec: Codec,
) {
    constructor(context: Context, prefsName: String = PREFS_NAME) : this(
        SharedPrefsStorage(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)),
        JsonCodec,
    )

    internal interface Storage {
        fun read(): String?
        fun write(value: String): Boolean
        fun remove(): Boolean
    }

    internal interface Codec {
        fun decode(raw: String): Map<String, Any?>
        fun encode(mappings: Map<String, String>): String
    }

    private class SharedPrefsStorage(private val prefs: SharedPreferences) : Storage {
        override fun read(): String? = prefs.getString(KEY, null)
        override fun write(value: String): Boolean = prefs.edit().putString(KEY, value).commit()
        override fun remove(): Boolean = prefs.edit().remove(KEY).commit()

        private companion object {
            const val KEY = "guide_mappings"
        }
    }

    /** Corrupt or missing data reads as no mappings rather than failing the guide. */
    fun load(): Map<String, String> {
        val raw = storage.read() ?: return emptyMap()
        return runCatching<Map<String, String>> {
            val result = LinkedHashMap<String, String>()
            for ((channelId, value) in codec.decode(raw)) {
                // One malformed record is a bad entry, not a reason to drop the user's other mappings.
                if (value is String) result[channelId] = value
            }
            result
        }.getOrElse { emptyMap() }
    }

    /** Commits synchronously. A mapping changes on a deliberate user action and the object is small. */
    fun save(mappings: Map<String, String>): Boolean = storage.write(codec.encode(mappings))

    fun clear(): Boolean = storage.remove()

    private object JsonCodec : Codec {
        override fun decode(raw: String): Map<String, Any?> {
            val json = JSONObject(raw)
            val result = LinkedHashMap<String, Any?>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val channelId = keys.next()
                result[channelId] = json.get(channelId)
            }
            return result
        }

        override fun encode(mappings: Map<String, String>): String {
            val json = JSONObject()
            for ((channelId, guideId) in mappings) json.put(channelId, guideId)
            return json.toString()
        }
    }

    companion object {
        const val PREFS_NAME = "tuneros_alpha"
    }
}

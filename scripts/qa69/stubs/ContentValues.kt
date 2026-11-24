package android.content

/** Minimal value container; the map is read by the scratch SQLite stand-in. */
class ContentValues {
    internal val map: LinkedHashMap<String, Any?> = LinkedHashMap()

    fun put(key: String, value: String?) {
        map[key] = value
    }

    fun put(key: String, value: Long?) {
        map[key] = value
    }

    fun put(key: String, value: Int?) {
        map[key] = value
    }

    fun putNull(key: String) {
        map[key] = null
    }
}

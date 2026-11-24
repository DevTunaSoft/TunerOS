package android.content

/** Bounded in-memory stand-in for the framework value bag. */
class ContentValues {
    private val values = LinkedHashMap<String, Any?>()
    fun put(key: String, value: String?) { values[key] = value }
    fun put(key: String, value: Long?) { values[key] = value }
    fun put(key: String, value: Int?) { values[key] = value }
    fun put(key: String, value: Boolean?) { values[key] = value }
    fun putNull(key: String) { values[key] = null }
    fun get(key: String): Any? = values[key]
    fun getAsString(key: String): String? = values[key]?.toString()
    fun getAsLong(key: String): Long? = values[key] as? Long ?: (values[key] as? Int)?.toLong()
    fun asMap(): Map<String, Any?> = values.toMap()
}

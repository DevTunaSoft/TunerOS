package android.os

/**
 * Synthetic JVM Bundle for FOCUS-41 unit tests. Declared in package android.* on purpose: AGP
 * puts test classes ahead of the mockable android.jar at runtime, so MainActivity's save-state
 * path can be exercised without a device or Robolectric. No real provider data is used.
 */
class Bundle {
    private val entries = LinkedHashMap<String, Any?>()

    fun putString(key: String?, value: String?) {
        entries[key ?: ""] = value
    }

    fun getString(key: String?): String? = entries[key ?: ""] as? String

    fun putLong(key: String?, value: Long) {
        entries[key ?: ""] = value
    }

    fun getLong(key: String?): Long = (entries[key ?: ""] as? Long) ?: 0L

    fun containsKey(key: String?): Boolean = entries.containsKey(key ?: "")

    fun remove(key: String?) {
        entries.remove(key ?: "")
    }

    val size: Int get() = entries.size

    fun values(): List<Any?> = entries.values.toList()

    fun entries(): Map<String, Any?> = entries.toMap()

    override fun toString(): String = "Bundle(size=$size)"
}

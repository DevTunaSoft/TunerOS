package android.content

/**
 * Synthetic Android surface for RESTORE-02 JVM unit tests only. These declarations live under the owned test dir, are
 * never shipped in the APK, and contain no real provider, account or device state.
 */
open class Context {
    companion object {
        const val MODE_PRIVATE = 0
    }

    open val applicationContext: Context get() = this

    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        throw UnsupportedOperationException("synthetic context")
}

interface SharedPreferences {
    fun getStringSet(key: String?, defValue: Set<String>?): Set<String>?
    fun getString(key: String?, defValue: String?): String?
    fun getInt(key: String?, defValue: Int): Int
    fun getLong(key: String?, defValue: Long): Long
    fun contains(key: String?): Boolean
    fun edit(): Editor

    interface Editor {
        fun putStringSet(key: String?, values: Set<String>?): Editor
        fun putString(key: String?, value: String?): Editor
        fun putInt(key: String?, value: Int): Editor
        fun putLong(key: String?, value: Long): Editor
        fun remove(key: String?): Editor
        fun commit(): Boolean
        fun apply()
    }
}

class ContentValues {
    internal val values = LinkedHashMap<String, Any?>()

    fun put(key: String?, value: String?) {
        values[key ?: return] = value
    }

    fun put(key: String?, value: Long?) {
        values[key ?: return] = value
    }

    fun putNull(key: String?) {
        values[key ?: return] = null
    }
}

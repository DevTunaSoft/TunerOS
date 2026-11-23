package android.content
open class Context {
    companion object { const val MODE_PRIVATE = 0 }
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        throw UnsupportedOperationException("host-only stub")
}
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun remove(key: String): Editor
        fun commit(): Boolean
    }
}

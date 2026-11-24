package android.database

import java.io.Closeable

/** Host-only signature mirror; Closeable makes Kotlin use{} work as on device. */
interface Cursor : Closeable {
    fun moveToFirst(): Boolean
    fun getString(index: Int): String?
    fun getLong(index: Int): Long
    fun getColumnIndexOrThrow(columnName: String): Int
    override fun close()
}

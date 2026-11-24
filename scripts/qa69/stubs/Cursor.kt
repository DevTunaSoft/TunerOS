package android.database

import java.io.Closeable

/** Minimal cursor contract with Closeable so production's use{} compiles and closes. */
interface Cursor : Closeable {
    override fun close()
    fun moveToNext(): Boolean
    fun moveToFirst(): Boolean
    fun getString(index: Int): String
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun isNull(index: Int): Boolean
    fun getColumnIndexOrThrow(columnName: String): Int
}

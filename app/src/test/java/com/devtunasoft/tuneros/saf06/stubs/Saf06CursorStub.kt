// SAF-06 synthetic Cursor double: fixed in-memory columns, close is a no-op.
package android.database

import java.io.Closeable

interface Cursor : Closeable {
    fun moveToFirst(): Boolean
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun getColumnIndexOrThrow(columnName: String): Int
    override fun close()
}

class Saf06Cursor(
    private val columns: Map<String, Int>,
    private val values: Map<Int, Any?>,
    private val hasRow: Boolean = true,
) : Cursor {
    override fun moveToFirst(): Boolean = hasRow
    override fun getString(columnIndex: Int): String? = values[columnIndex] as? String
    override fun getLong(columnIndex: Int): Long = values[columnIndex] as? Long ?: 0L
    override fun getColumnIndexOrThrow(columnName: String): Int =
        columns[columnName] ?: error("synthetic cursor has no column $columnName")
    override fun close() = Unit
}

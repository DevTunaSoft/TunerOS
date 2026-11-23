// QA-04 JVM harness stub for android.database.Cursor. Synthetic scratch rows only.
package android.database

import java.io.Closeable

class Cursor(
    private val columnNames: List<String>,
    private val rowValues: List<List<Any?>>,
) : Closeable {
    private var position = -1

    fun moveToFirst(): Boolean {
        position = if (rowValues.isEmpty()) -1 else 0
        return position >= 0
    }

    fun moveToNext(): Boolean {
        position++
        return position < rowValues.size
    }

    val count: Int get() = rowValues.size

    fun getColumnIndexOrThrow(name: String): Int {
        val index = columnNames.indexOf(name)
        if (index < 0) throw IllegalArgumentException("No QA column $name")
        return index
    }

    // Android's Cursor.getString is a platform type; production passes the
    // result straight into non-null String fields, so keep that shape here.
    fun getString(index: Int): String = cell(index)?.toString() ?: ""

    fun getLong(index: Int): Long = when (val value = cell(index)) {
        is Number -> value.toLong()
        is String -> value.toLong()
        else -> throw IllegalStateException("QA cell $index is not an integer")
    }

    fun getInt(index: Int): Int = when (val value = cell(index)) {
        is Number -> value.toInt()
        is String -> value.toInt()
        else -> throw IllegalStateException("QA cell $index is not an integer")
    }

    fun isNull(index: Int): Boolean = cell(index) == null

    private fun cell(index: Int): Any? = rowValues[position][index]

    override fun close() = Unit
}

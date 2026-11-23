// DVR-03 Cursor stub: only the bounded read surface the schedule store uses.
// Android declares Cursor as an interface; production bytecode expects that shape.
// Synthetic scratch rows only.
package android.database

import java.io.Closeable

interface Cursor : Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getColumnIndexOrThrow(name: String): Int
    fun getString(index: Int): String
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun isNull(index: Int): Boolean
}

class Dvr03Cursor(
    private val columnNames: List<String>,
    private val rowValues: List<List<Any?>>,
) : Cursor {
    private var position = -1

    override fun moveToFirst(): Boolean {
        position = if (rowValues.isEmpty()) -1 else 0
        return position >= 0
    }

    override fun moveToNext(): Boolean {
        position++
        return position < rowValues.size
    }

    override fun getColumnIndexOrThrow(name: String): Int =
        columnNames.indexOf(name).takeIf { it >= 0 }
            ?: throw IllegalArgumentException("No DVR-03 column $name")

    override fun getString(index: Int): String = cell(index)?.toString() ?: ""
    override fun getLong(index: Int): Long = number(index).toLong()
    override fun getInt(index: Int): Int = number(index).toInt()
    override fun isNull(index: Int): Boolean = cell(index) == null

    private fun number(index: Int): Number = cell(index) as? Number
        ?: throw IllegalStateException("DVR-03 cell $index is not numeric")

    private fun cell(index: Int): Any? = rowValues[position][index]

    override fun close() = Unit
}

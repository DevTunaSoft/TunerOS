package android.database

import java.io.Closeable

/** Cursor stub: production must close every query through use() or [closed] stays false. */
open class Cursor : Closeable {
    var closed = false
        private set
    var position = -1
        private set
    var names: List<String> = emptyList()
    var rows: List<Map<String, Any?>> = emptyList()

    override fun close() { closed = true }

    fun moveToNext(): Boolean {
        if (position + 1 >= rows.size) return false
        position++
        return true
    }

    fun getColumnIndexOrThrow(name: String): Int {
        val index = names.indexOf(name)
        if (index < 0) throw IllegalArgumentException("missing column: $name")
        return index
    }

    private fun value(index: Int): Any? {
        if (index !in names.indices) throw IllegalArgumentException("column index: $index")
        return rows[position][names[index]]
    }

    fun getString(index: Int): String = value(index)?.toString() ?: ""
    fun getLong(index: Int): Long = value(index) as? Long
        ?: (value(index) as? Int)?.toLong()
        ?: (value(index) as? String)?.toLongOrNull()
        ?: 0L
    fun getInt(index: Int): Int = getLong(index).toInt()
    fun isNull(index: Int): Boolean = value(index) == null
    fun getCount(): Int = rows.size
}

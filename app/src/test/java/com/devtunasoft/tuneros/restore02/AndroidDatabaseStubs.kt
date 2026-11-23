package android.database

import android.database.sqlite.SQLiteDatabase

/** Synthetic Cursor surface used by SeriesRuleStore.all() and .contains(). */
interface Cursor : java.io.Closeable {
    val count: Int
    fun moveToNext(): Boolean
    fun isNull(index: Int): Boolean
    fun getString(index: Int): String?
    override fun close()
}

/** Synthetic DatabaseUtils surface used by SeriesRuleStore.add(). */
object DatabaseUtils {
    @JvmStatic
    fun queryNumEntries(db: SQLiteDatabase?, table: String?): Long = db?.rowCount(table ?: "") ?: 0L
}

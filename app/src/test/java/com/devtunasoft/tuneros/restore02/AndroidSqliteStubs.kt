package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import java.util.concurrent.ConcurrentHashMap

/** Synthetic minimal SQLite surface: one in-memory table, only the operations the tested stores use. */
open class SQLiteDatabase {
    interface CursorFactory

    data class Row(val id: String, val titleKey: String, val channelId: String?, val createdEpoch: Long)

    companion object {
        const val CONFLICT_IGNORE = 4
        private const val TABLE = "series_rules"

        /** Every synthetic SeriesRuleStore database is registered here by its synthetic name. */
        val opened = ConcurrentHashMap<String, SQLiteDatabase>()
    }

    internal val rows = ArrayList<Row>()
    var failNextInsert = false
    var dropInserts = false

    fun rowCount(table: String): Long = if (table == TABLE) rows.size.toLong() else 0L

    fun execSQL(sql: String) {
        // The production store's CREATE TABLE statement is a no-op for this in-memory table.
    }

    fun beginTransaction() = Unit
    fun setTransactionSuccessful() = Unit

    fun endTransaction() = Unit

    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        if (table != TABLE) return 0
        if (whereClause == null) {
            val removed = rows.size
            rows.clear()
            return removed
        }
        var removed = 0
        val iterator = rows.iterator()
        while (iterator.hasNext()) {
            val row = iterator.next()
            if (whereClause == "id = ?" && whereArgs?.firstOrNull() == row.id) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        initialValues: ContentValues?,
        conflictAlgorithm: Int,
    ): Long {
        if (table != TABLE || initialValues == null) return -1L
        if (failNextInsert) {
            failNextInsert = false
            return -1L
        }
        val values = initialValues.values
        val id = values["id"] as? String ?: return -1L
        if (dropInserts) return rows.size + 1L
        if (rows.any { it.id == id }) return -1L
        rows += Row(
            id = id,
            titleKey = values["title_key"] as? String ?: return -1L,
            channelId = values["channel_id"] as? String,
            createdEpoch = values["created_epoch"] as? Long ?: return -1L,
        )
        return rows.size.toLong()
    }

    fun query(
        table: String,
        columns: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
    ): Cursor {
        if (table != TABLE) return EmptyCursor
        var result = rows.toList()
        if (selection == "id = ?" && selectionArgs != null) {
            result = result.filter { it.id == selectionArgs.firstOrNull() }
        }
        if (orderBy?.contains("created_epoch") == true) result = result.sortedWith(compareBy({ it.createdEpoch }, { it.id }))
        return ListCursor(result)
    }

    private inner class ListCursor(private val data: List<Row>) : Cursor {
        private var position = -1
        override val count: Int get() = data.size
        override fun moveToNext(): Boolean {
            position++
            return position < data.size
        }
        override fun isNull(index: Int): Boolean = data[position].channelId == null
        override fun getString(index: Int): String? = when (index) {
            0 -> data[position].titleKey
            1 -> data[position].channelId
            else -> null
        }
        override fun close() = Unit
    }

    private object EmptyCursor : Cursor {
        override val count: Int get() = 0
        override fun moveToNext(): Boolean = false
        override fun isNull(index: Int): Boolean = true
        override fun getString(index: Int): String? = null
        override fun close() = Unit
    }
}

/** Synthetic helper that hands each named store its own in-memory database, like the real SQLiteOpenHelper. */
abstract class SQLiteOpenHelper(
    @Suppress("UNUSED_PARAMETER") context: Context?,
    name: String?,
    @Suppress("UNUSED_PARAMETER") factory: SQLiteDatabase.CursorFactory?,
    @Suppress("UNUSED_PARAMETER") version: Int,
) {
    open val writableDatabase: SQLiteDatabase get() = database()
    open val readableDatabase: SQLiteDatabase get() = database()

    abstract fun onCreate(db: SQLiteDatabase)
    abstract fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)

    private val dbName: String = name ?: "synthetic-$this"

    private fun database(): SQLiteDatabase = SQLiteDatabase.opened.getOrPut(dbName) {
        SQLiteDatabase().also { onCreate(it) }
    }
}

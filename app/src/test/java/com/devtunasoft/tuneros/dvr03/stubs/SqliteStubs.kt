// DVR-03 deterministic in-memory SQLite stub for the real RecordingScheduleStore.
// It evaluates only the bounded SQL shapes that store actually emits. All data
// is synthetic scratch data; no device database or recording is touched.
package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.Dvr03Cursor
import java.io.Closeable

object Dvr03FakeSqlite {
    val rows = LinkedHashMap<String, MutableList<MutableMap<String, Any?>>>()
    val versions = HashMap<String, Int>()
    var createCalls = 0
        private set
    var upgradeCalls = 0
        private set
    var lastUpgrade: Pair<Int, Int>? = null
        private set

    fun reset() {
        rows.clear()
        versions.clear()
        createCalls = 0
        upgradeCalls = 0
        lastUpgrade = null
    }

    fun table(name: String): MutableList<MutableMap<String, Any?>> =
        rows.getOrPut(name) { ArrayList() }

    fun noteCreate() { createCalls++ }

    fun noteUpgrade(old: Int, new: Int) {
        upgradeCalls++
        lastUpgrade = old to new
    }

    /** Models an on-device schema-version change without dropping the row file. */
    fun markStoredVersion(name: String, version: Int) { versions[name] = version }
}

open class SQLiteDatabase internal constructor(private val qaName: String) : Closeable {
    companion object {
        const val CONFLICT_IGNORE = 4
    }

    /** Platform shape used by SQLiteOpenHelper's constructor signature. */
    open class CursorFactory

    private val table get() = Dvr03FakeSqlite.table("schedules")

    fun execSQL(sql: String) {
        if (sql.contains("CREATE TABLE", ignoreCase = true)) Dvr03FakeSqlite.noteCreate()
    }

    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflict: Int,
    ): Long {
        check(table == "schedules") { "DVR-03 stub only models the schedules table" }
        val row = LinkedHashMap(values.snapshot())
        val id = row["id"]?.toString() ?: error("DVR-03 row has no id")
        if (this.table.any { it["id"] == id }) return -1L
        this.table += row
        return (this.table.size).toLong()
    }

    fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
    ): Int {
        check(table == "schedules")
        var changed = 0
        for (row in this.table) {
            if (matches(row, whereClause, whereArgs)) {
                row.putAll(values.snapshot())
                changed++
            }
        }
        return changed
    }

    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        check(table == "schedules")
        val before = this.table.size
        this.table.removeAll { matches(it, whereClause, whereArgs) }
        return before - this.table.size
    }

    fun query(
        table: String,
        columns: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
        limit: String?,
    ): Cursor {
        check(table == "schedules")
        val selected = this.table.filter { matches(it, selection, selectionArgs) }.toMutableList()
        when {
            orderBy?.contains("start_epoch", ignoreCase = true) == true ->
                selected.sortWith(compareBy<MutableMap<String, Any?>> { num(it["start_epoch"]) }
                    .thenBy { it["id"].toString() })
            orderBy?.contains("id", ignoreCase = true) == true ->
                selected.sortBy { it["id"].toString() }
        }
        val capped = if (limit == null) selected else selected.take(num(limit).toInt())
        val names = columns?.toList()
            ?: listOf("id", "title", "channel_id", "secure_source_key", "start_epoch",
                "end_epoch", "state", "exact_alarm", "last_error")
        return Dvr03Cursor(names, capped.map { row -> names.map(row::get) })
    }

    private fun matches(
        row: Map<String, Any?>,
        where: String?,
        args: Array<String>?,
    ): Boolean {
        if (where.isNullOrBlank()) return true
        return when (where) {
            "id=?" -> row["id"] == args?.get(0)
            "state=? AND end_epoch>?" ->
                row["state"] == args?.get(0) && num(row["end_epoch"]) > num(args?.get(1))
            "state=? AND end_epoch>? AND (start_epoch>? OR (start_epoch=? AND id>?))" -> {
                val state = args?.get(0)
                val end = num(args?.get(1))
                val start = num(args?.get(2))
                val sameStart = num(args?.get(3))
                row["state"] == state && num(row["end_epoch"]) > end &&
                    (num(row["start_epoch"]) > start ||
                        (num(row["start_epoch"]) == sameStart && row["id"].toString() > args?.get(4).orEmpty()))
            }
            "state IN ('PREPARING','RECOVERING')" ->
                row["state"] in setOf("PREPARING", "RECOVERING")
            "state IN ('PREPARING','RECOVERING') AND id>?" ->
                row["state"] in setOf("PREPARING", "RECOVERING") && row["id"].toString() > args?.get(0).orEmpty()
            else -> throw IllegalArgumentException("DVR-03 stub does not model: $where")
        }
    }

    private fun num(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLong()
        else -> error("DVR-03 expected numeric value, got $value")
    }

    override fun close() = Unit
}

abstract class SQLiteOpenHelper(
    context: Context,
    databaseName: String?,
    factory: SQLiteDatabase.CursorFactory?,
    private val qaVersion: Int,
) : Closeable {
    private val qaName = databaseName ?: "dvr03-default.db"
    private val db = SQLiteDatabase(qaName)

    val writableDatabase: SQLiteDatabase get() = db
    val readableDatabase: SQLiteDatabase get() = db

    init {
        val stored = Dvr03FakeSqlite.versions[qaName]
        if (stored == null) {
            onCreate(db)
            Dvr03FakeSqlite.versions[qaName] = qaVersion
        } else if (stored != qaVersion) {
            onUpgrade(db, stored, qaVersion)
            Dvr03FakeSqlite.noteUpgrade(stored, qaVersion)
            Dvr03FakeSqlite.versions[qaName] = qaVersion
        }
    }

    open fun onCreate(db: SQLiteDatabase) = Unit

    open fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun close() = Unit
}

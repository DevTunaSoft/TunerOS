// QA-04 JVM harness: real SQLite (via a bounded Python bridge) behind the tiny
// subset of the android.database API used by RecordingScheduleStore. The
// production Kotlin source is compiled unchanged against these stubs.
package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

open class SQLiteDatabase internal constructor(private val qaName: String) : Closeable {
    companion object {
        const val CONFLICT_IGNORE = 4
    }

    fun execSQL(sql: String) {
        QaSqliteBridge.request("exec", qaName, b64(sql))
    }

    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflict: Int,
    ): Long {
        val snapshot = values.snapshot()
        val tokens = ArrayList<String>()
        tokens += "insert"
        tokens += qaName
        tokens += table
        for ((column, value) in snapshot) {
            tokens += column
            tokens += encode(value)
        }
        return QaSqliteBridge.request(*tokens.toTypedArray()).toLong()
    }

    fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
    ): Int {
        val snapshot = values.snapshot()
        val tokens = ArrayList<String>()
        tokens += "update"
        tokens += qaName
        tokens += table
        tokens += b64(whereClause ?: "")
        tokens += snapshot.size.toString()
        for ((column, value) in snapshot) {
            tokens += column
            tokens += encode(value)
        }
        tokens += (whereArgs?.size ?: 0).toString()
        whereArgs?.forEach { tokens += encode(it) }
        return QaSqliteBridge.request(*tokens.toTypedArray()).toInt()
    }

    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        val tokens = ArrayList<String>()
        tokens += "delete"
        tokens += qaName
        tokens += table
        tokens += b64(whereClause ?: "")
        tokens += (whereArgs?.size ?: 0).toString()
        whereArgs?.forEach { tokens += encode(it) }
        return QaSqliteBridge.request(*tokens.toTypedArray()).toInt()
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
        val sql = buildString {
            append("SELECT ").append(columns?.joinToString(", ") ?: "*")
            append(" FROM ").append(table)
            if (!selection.isNullOrEmpty()) append(" WHERE ").append(selection)
            if (!groupBy.isNullOrEmpty()) append(" GROUP BY ").append(groupBy)
            if (!having.isNullOrEmpty()) append(" HAVING ").append(having)
            if (!orderBy.isNullOrEmpty()) append(" ORDER BY ").append(orderBy)
            if (!limit.isNullOrEmpty()) append(" LIMIT ").append(limit)
        }
        val tokens = ArrayList<String>()
        tokens += "query"
        tokens += qaName
        tokens += b64(sql)
        tokens += (selectionArgs?.size ?: 0).toString()
        selectionArgs?.forEach { tokens += encode(it) }
        val response = QaSqliteBridge.request(*tokens.toTypedArray()).split(QaSqliteBridge.SEP)
        check(response[0] == "ROWS") { "Unexpected QA SQLite response: ${response[0]}" }
        val rowCount = response[1].toInt()
        QaSqliteBridge.noteQueryRows(rowCount)
        val names = response.drop(2).takeWhile { it != QA_ROWS_END }
        val encodedRows = response.drop(2 + names.size + 1)
        check(encodedRows.size == rowCount) { "QA SQLite row count mismatch" }
        val rows = encodedRows.map { row ->
            row.split(QaSqliteBridge.GS).map { QaSqliteBridge.decode(it) }
        }
        return Cursor(names, rows)
    }

    override fun close() {
        QaSqliteBridge.request("close", qaName)
    }
}

private const val QA_ROWS_END = "ENDCOLS"

abstract class SQLiteOpenHelper(
    context: Context,
    databaseName: String?,
    factory: Any?,
    private val qaVersion: Int,
) : Closeable {
    private val qaName = databaseName ?: "qa04-default.db"
    private var database: SQLiteDatabase? = null

    val writableDatabase: SQLiteDatabase get() = open()
    val readableDatabase: SQLiteDatabase get() = open()

    private fun open(): SQLiteDatabase {
        database?.let { return it }
        val existed = QaSqliteBridge.request("exists", qaName) == "YES"
        val db = SQLiteDatabase(qaName)
        QaSqliteBridge.request("open", qaName)
        if (qaName == "qa04-upgrade.db" &&
            System.getProperty("qa04.fault.wipeOnOpen") == "true"
        ) {
            // Negative control: an upgrade that silently drops durable schedule
            // rows while leaving the schema readable.
            if (existed) db.execSQL("DELETE FROM schedules")
            else db.execSQL("DROP TABLE IF EXISTS schedules")
        }
        if (!existed) {
            onCreate(db)
        } else {
            val oldVersion = QaSqliteBridge.request("version", qaName).toInt()
            if (oldVersion < qaVersion) onUpgrade(db, oldVersion, qaVersion)
            if (oldVersion > qaVersion) onDowngrade(db, oldVersion, qaVersion)
        }
        QaSqliteBridge.request("setversion", qaName, qaVersion.toString())
        database = db
        return db
    }

    abstract fun onCreate(db: SQLiteDatabase)
    open fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    open fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun close() {
        database?.close()
        database = null
    }
}

internal object QaSqliteBridge {
    const val SEP = "\u001f"
    const val GS = "\u001d"

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Largest row set returned by any single query since the last reset. */
    var maxQueryRows: Int = 0
        private set

    fun resetQueryRows() { maxQueryRows = 0 }

    fun noteQueryRows(count: Int) {
        if (count > maxQueryRows) maxQueryRows = count
    }

    @Synchronized
    fun request(vararg tokens: String): String {
        ensureProcess()
        val out = writer ?: error("QA SQLite bridge writer unavailable")
        val input = reader ?: error("QA SQLite bridge reader unavailable")
        out.write(tokens.joinToString(SEP))
        out.newLine()
        out.flush()
        return input.readLine() ?: error("QA SQLite bridge closed unexpectedly")
    }

    @Synchronized
    fun closeAll() {
        val running = process ?: return
        runCatching { writer?.write("shutdown"); writer?.newLine(); writer?.flush() }
        runCatching { running.waitFor(5, TimeUnit.SECONDS) }
        runCatching { running.destroy() }
        process = null
        writer = null
        reader = null
        maxQueryRows = 0
    }

    private fun ensureProcess() {
        if (process != null) return
        val python = System.getProperty("qa04.python")
            ?: error("qa04.python system property is required")
        val script = System.getProperty("qa04.bridge")
            ?: error("qa04.bridge system property is required")
        val started = ProcessBuilder(python, script)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process = started
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
        reader = BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8))
    }

    fun encode(value: Any?): String = when (value) {
        null -> "n"
        is String -> "s" + Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        is Number -> "i" + value.toString()
        else -> error("Unsupported QA SQLite value ${value.javaClass.simpleName}")
    }

    fun decode(token: String): Any? = when (token.firstOrNull()) {
        'n' -> null
        's' -> String(Base64.getDecoder().decode(token.drop(1)), StandardCharsets.UTF_8)
        'i' -> token.drop(1).toLong()
        else -> error("Unsupported QA SQLite token")
    }
}

private fun encode(value: Any?): String = QaSqliteBridge.encode(value)

private fun b64(text: String): String =
    Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

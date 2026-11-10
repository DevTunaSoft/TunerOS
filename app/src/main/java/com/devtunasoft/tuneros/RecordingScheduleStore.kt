package com.devtunasoft.tuneros

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class RecordingScheduleEntry(
    val id: String,
    val title: String,
    val channelId: String,
    val secureSourceKey: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val state: String,
    val exactAlarm: Boolean,
    val lastError: String?,
)

internal class RecordingScheduleStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "recording_schedules.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE schedules(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                secure_source_key TEXT NOT NULL,
                start_epoch INTEGER NOT NULL,
                end_epoch INTEGER NOT NULL,
                state TEXT NOT NULL,
                exact_alarm INTEGER NOT NULL DEFAULT 0,
                last_error TEXT
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX schedules_start_idx ON schedules(start_epoch)")
        db.execSQL("CREATE INDEX schedules_state_idx ON schedules(state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsert(entry: RecordingScheduleEntry) {
        require(entry.endEpochSeconds > entry.startEpochSeconds)
        val values = ContentValues().apply {
            put("id", entry.id)
            put("title", entry.title)
            put("channel_id", entry.channelId)
            put("secure_source_key", entry.secureSourceKey)
            put("start_epoch", entry.startEpochSeconds)
            put("end_epoch", entry.endEpochSeconds)
            put("state", entry.state)
            put("exact_alarm", if (entry.exactAlarm) 1 else 0)
            if (entry.lastError == null) putNull("last_error") else put("last_error", entry.lastError)
        }
        writableDatabase.insertWithOnConflict("schedules", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(id: String): RecordingScheduleEntry? =
        readableDatabase.query(
            "schedules",
            COLUMNS,
            "id=?",
            arrayOf(id),
            null, null, null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.toEntry()
        }

    fun pendingAfter(epochSeconds: Long, limit: Int = 200): List<RecordingScheduleEntry> {
        require(limit in 1..500)
        val result = ArrayList<RecordingScheduleEntry>(minOf(limit, 32))
        readableDatabase.query(
            "schedules",
            COLUMNS,
            "state=? AND end_epoch>?",
            arrayOf("SCHEDULED", epochSeconds.toString()),
            null, null,
            "start_epoch ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEntry()
        }
        return result
    }

    fun updateAlarmMode(id: String, exact: Boolean, lastError: String?) {
        val values = ContentValues().apply {
            put("exact_alarm", if (exact) 1 else 0)
            if (lastError == null) putNull("last_error") else put("last_error", lastError)
        }
        writableDatabase.update("schedules", values, "id=?", arrayOf(id))
    }

    fun markStarted(id: String) {
        val values = ContentValues().apply {
            put("state", "STARTED")
            putNull("last_error")
        }
        writableDatabase.update("schedules", values, "id=?", arrayOf(id))
    }

    fun markFailed(id: String, reason: String) {
        val values = ContentValues().apply {
            put("state", "FAILED")
            put("last_error", reason.take(200))
        }
        writableDatabase.update("schedules", values, "id=?", arrayOf(id))
    }

    fun delete(id: String) {
        writableDatabase.delete("schedules", "id=?", arrayOf(id))
    }

    private fun android.database.Cursor.toEntry(): RecordingScheduleEntry =
        RecordingScheduleEntry(
            id = getString(getColumnIndexOrThrow("id")),
            title = getString(getColumnIndexOrThrow("title")),
            channelId = getString(getColumnIndexOrThrow("channel_id")),
            secureSourceKey = getString(getColumnIndexOrThrow("secure_source_key")),
            startEpochSeconds = getLong(getColumnIndexOrThrow("start_epoch")),
            endEpochSeconds = getLong(getColumnIndexOrThrow("end_epoch")),
            state = getString(getColumnIndexOrThrow("state")),
            exactAlarm = getInt(getColumnIndexOrThrow("exact_alarm")) != 0,
            lastError = getColumnIndexOrThrow("last_error").let { if (isNull(it)) null else getString(it) },
        )

    companion object {
        private val COLUMNS = arrayOf(
            "id","title","channel_id","secure_source_key","start_epoch","end_epoch",
            "state","exact_alarm","last_error",
        )
    }
}

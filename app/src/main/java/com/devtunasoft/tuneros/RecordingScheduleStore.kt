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

internal data class OrphanedPreparation(val id: String, val secureSourceKey: String)

internal class RecordingScheduleStore(
    context: Context,
    databaseName: String = "recording_schedules.db",
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {

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

    /**
     * Reserve a schedule ID once; never overwrite a previously scheduled or
     * started recording or its encrypted source key.
     */
    fun insertIfAbsent(entry: RecordingScheduleEntry): Boolean {
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
        return writableDatabase.insertWithOnConflict(
            "schedules", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    /** The alarm cannot be armed until its secure source is fully written. */
    fun markScheduledIfPreparing(id: String): Boolean {
        val values = ContentValues().apply {
            put("state", "SCHEDULED")
            putNull("last_error")
        }
        return writableDatabase.update(
            "schedules", values, "id=? AND state=?",
            arrayOf(id, "PREPARING"),
        ) == 1
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

    /**
     * Keyset-paged pending jobs, ordered by (start_epoch, id).
     * Re-arm all jobs after a reboot without loading the entire schedule into RAM.
     */
    fun pendingAfter(
        epochSeconds: Long,
        limit: Int = 200,
        after: RecordingScheduleEntry? = null,
    ): List<RecordingScheduleEntry> {
        require(limit in 1..500)
        val selection: String
        val args: Array<String>
        if (after == null) {
            selection = "state=? AND end_epoch>?"
            args = arrayOf("SCHEDULED", epochSeconds.toString())
        } else {
            selection = "state=? AND end_epoch>? AND (start_epoch>? OR (start_epoch=? AND id>?))"
            val start = after.startEpochSeconds.toString()
            args = arrayOf("SCHEDULED", epochSeconds.toString(), start, start, after.id)
        }
        val result = ArrayList<RecordingScheduleEntry>(minOf(limit, 32))
        readableDatabase.query(
            "schedules",
            COLUMNS,
            selection,
            args,
            null, null,
            "start_epoch ASC, id ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEntry()
        }
        return result
    }

    /**
     * Only unfinished setup states are eligible. Keyset paging avoids loading
     * potentially many schedule rows at once or rescanning deleted offsets.
     */
    fun orphanedPreparationsAfter(afterId: String?, limit: Int = 32): List<OrphanedPreparation> {
        require(limit in 1..128)
        val result = ArrayList<OrphanedPreparation>(minOf(limit, 32))
        readableDatabase.query(
            "schedules",
            arrayOf("id", "secure_source_key"),
            if (afterId == null) "state IN ('PREPARING','RECOVERING')"
                else "state IN ('PREPARING','RECOVERING') AND id>?",
            if (afterId == null) null else arrayOf(afterId),
            null, null, "id ASC", limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += OrphanedPreparation(cursor.getString(0), cursor.getString(1))
            }
        }
        return result
    }

    /**
     * Claim only the same inert reservation. RECOVERING stays collision-proof
     * until all three encrypted source fields are cleared durably.
     */
    fun markRecoveringIfOrphaned(id: String, secureSourceKey: String): Boolean {
        val values = ContentValues().apply { put("state", "RECOVERING") }
        return writableDatabase.update(
            "schedules", values,
            "id=? AND secure_source_key=? AND state IN ('PREPARING','RECOVERING')",
            arrayOf(id, secureSourceKey),
        ) == 1
    }

    fun deleteRecovered(id: String, secureSourceKey: String): Boolean =
        writableDatabase.delete(
            "schedules",
            "id=? AND secure_source_key=? AND state='RECOVERING'",
            arrayOf(id, secureSourceKey),
        ) == 1

    fun findOverlap(
        id: String,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
    ): RecordingScheduleEntry? =
        readableDatabase.query(
            "schedules",
            COLUMNS,
            "state IN ('PREPARING','RECOVERING','SCHEDULED','STARTED') AND id<>? AND start_epoch<? AND end_epoch>?",
            arrayOf(id, endEpochSeconds.toString(), startEpochSeconds.toString()),
            null, null,
            "start_epoch ASC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.toEntry()
        }

    fun updateAlarmMode(id: String, exact: Boolean, lastError: String?) {
        val values = ContentValues().apply {
            put("exact_alarm", if (exact) 1 else 0)
            if (lastError == null) putNull("last_error") else put("last_error", lastError)
        }
        writableDatabase.update("schedules", values, "id=?", arrayOf(id))
    }

    /**
     * A launch request is not proof of recording. Only the service that has
     * successfully entered foreground mode may acknowledge an existing,
     * matching SCHEDULED reservation.
     */
    fun markStartedIfScheduled(id: String, secureSourceKey: String): Boolean {
        val values = ContentValues().apply {
            put("state", "STARTED")
            putNull("last_error")
        }
        return writableDatabase.update(
            "schedules", values, "id=? AND state=? AND secure_source_key=?",
            arrayOf(id, "SCHEDULED", secureSourceKey),
        ) == 1
    }

    fun markFailedIfScheduled(
        id: String,
        secureSourceKey: String,
        reason: String,
    ): Boolean {
        val values = ContentValues().apply {
            put("state", "FAILED")
            put("last_error", reason.take(200))
        }
        return writableDatabase.update(
            "schedules", values, "id=? AND state=? AND secure_source_key=?",
            arrayOf(id, "SCHEDULED", secureSourceKey),
        ) == 1
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

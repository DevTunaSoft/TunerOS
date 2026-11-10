package com.devtunasoft.tuneros

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class RecordingCatalogEntry(
    val id: String,
    val title: String,
    val channelId: String,
    val state: String,
    val startedEpochSeconds: Long?,
    val stoppedEpochSeconds: Long?,
    val bytesWritten: Long,
    val filePath: String?,
    val failureReason: String?,
)

internal class RecordingCatalog(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "recordings.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE recordings_v2(
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    channel_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    started_epoch INTEGER,
                    stopped_epoch INTEGER,
                    bytes_written INTEGER NOT NULL DEFAULT 0,
                    file_path TEXT,
                    failure_reason TEXT
                )""".trimIndent(),
            )
            db.execSQL(
                """INSERT INTO recordings_v2(
                    id,title,channel_id,state,started_epoch,stopped_epoch,bytes_written,file_path,failure_reason
                ) SELECT
                    id,title,channel_id,state,started_epoch,stopped_epoch,bytes_written,file_path,failure_reason
                FROM recordings""".trimIndent(),
            )
            db.execSQL("DROP TABLE recordings")
            db.execSQL("ALTER TABLE recordings_v2 RENAME TO recordings")
            createIndexes(db)
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE recordings(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                state TEXT NOT NULL,
                started_epoch INTEGER,
                stopped_epoch INTEGER,
                bytes_written INTEGER NOT NULL DEFAULT 0,
                file_path TEXT,
                failure_reason TEXT
            )""".trimIndent(),
        )
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX recordings_state_idx ON recordings(state)")
        db.execSQL("CREATE INDEX recordings_started_idx ON recordings(started_epoch DESC)")
    }

    fun upsertStart(id: String, title: String, channelId: String, startedEpochSeconds: Long) {
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("channel_id", channelId)
            put("state", "RECORDING")
            put("started_epoch", startedEpochSeconds)
            put("bytes_written", 0L)
            putNull("stopped_epoch")
            putNull("file_path")
            putNull("failure_reason")
        }
        writableDatabase.insertWithOnConflict("recordings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateProgress(id: String, bytesWritten: Long) {
        val values = ContentValues().apply { put("bytes_written", bytesWritten) }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id))
    }

    fun finish(
        id: String,
        state: String,
        stoppedEpochSeconds: Long,
        bytesWritten: Long,
        filePath: String?,
        failureReason: String?,
    ) {
        val values = ContentValues().apply {
            put("state", state)
            put("stopped_epoch", stoppedEpochSeconds)
            put("bytes_written", bytesWritten)
            if (filePath == null) putNull("file_path") else put("file_path", filePath)
            if (failureReason == null) putNull("failure_reason") else put("failure_reason", failureReason)
        }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id))
    }

    fun recent(limit: Int = 100): List<RecordingCatalogEntry> {
        require(limit in 1..500)
        val result = ArrayList<RecordingCatalogEntry>(minOf(limit, 100))
        readableDatabase.query(
            "recordings",
            arrayOf("id","title","channel_id","state","started_epoch","stopped_epoch","bytes_written","file_path","failure_reason"),
            null, null, null, null,
            "COALESCE(started_epoch,0) DESC",
            limit.toString(),
        ).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("id")
            val titleCol = cursor.getColumnIndexOrThrow("title")
            val channelCol = cursor.getColumnIndexOrThrow("channel_id")
            val stateCol = cursor.getColumnIndexOrThrow("state")
            val startedCol = cursor.getColumnIndexOrThrow("started_epoch")
            val stoppedCol = cursor.getColumnIndexOrThrow("stopped_epoch")
            val bytesCol = cursor.getColumnIndexOrThrow("bytes_written")
            val pathCol = cursor.getColumnIndexOrThrow("file_path")
            val failureCol = cursor.getColumnIndexOrThrow("failure_reason")
            while (cursor.moveToNext()) {
                result += RecordingCatalogEntry(
                    id = cursor.getString(idCol),
                    title = cursor.getString(titleCol),
                    channelId = cursor.getString(channelCol),
                    state = cursor.getString(stateCol),
                    startedEpochSeconds = if (cursor.isNull(startedCol)) null else cursor.getLong(startedCol),
                    stoppedEpochSeconds = if (cursor.isNull(stoppedCol)) null else cursor.getLong(stoppedCol),
                    bytesWritten = cursor.getLong(bytesCol),
                    filePath = if (cursor.isNull(pathCol)) null else cursor.getString(pathCol),
                    failureReason = if (cursor.isNull(failureCol)) null else cursor.getString(failureCol),
                )
            }
        }
        return result
    }
}

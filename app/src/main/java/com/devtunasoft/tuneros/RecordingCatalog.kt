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
    val storageLocation: String?,
    val failureReason: String?,
    val isProtected: Boolean,
)

internal class RecordingCatalog(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "recordings.db", null, 3) {

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
        if (oldVersion < 3) {
            db.execSQL(
                "ALTER TABLE recordings ADD COLUMN protected INTEGER NOT NULL DEFAULT 0",
            )
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
                failure_reason TEXT,
                protected INTEGER NOT NULL DEFAULT 0
            )""".trimIndent(),
        )
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX recordings_state_idx ON recordings(state)")
        db.execSQL("CREATE INDEX recordings_started_idx ON recordings(started_epoch DESC)")
    }

    /** A recording ID is immutable: never clobber an existing recording or protection flag. */
    fun upsertStart(id: String, title: String, channelId: String, startedEpochSeconds: Long): Boolean {
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
        val db = writableDatabase
        val inserted = db.insertWithOnConflict(
            "recordings",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return inserted != -1L
    }

    fun updateProgress(id: String, bytesWritten: Long) {
        val values = ContentValues().apply { put("bytes_written", bytesWritten) }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id))
    }

    fun setStorageLocation(id: String, storageLocation: String) {
        val values = ContentValues().apply { put("file_path", storageLocation) }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id))
    }

    fun setProtected(id: String, protected: Boolean): Int {
        val values = ContentValues().apply { put("protected", if (protected) 1 else 0) }
        return writableDatabase.update("recordings", values, "id=?", arrayOf(id))
    }

    fun delete(id: String): Int =
        writableDatabase.delete("recordings", "id=?", arrayOf(id))

    fun markInterruptedRecordings(stoppedEpochSeconds: Long): Int {
        val values = ContentValues().apply {
            put("state", "PARTIAL")
            put("stopped_epoch", stoppedEpochSeconds)
            put("failure_reason", "Recorder process interrupted")
        }
        return writableDatabase.update("recordings", values, "state=?", arrayOf("RECORDING"))
    }

    fun finish(
        id: String,
        state: String,
        stoppedEpochSeconds: Long,
        bytesWritten: Long,
        storageLocation: String?,
        failureReason: String?,
    ) {
        val values = ContentValues().apply {
            put("state", state)
            put("stopped_epoch", stoppedEpochSeconds)
            put("bytes_written", bytesWritten)
            if (storageLocation == null) putNull("file_path") else put("file_path", storageLocation)
            if (failureReason == null) putNull("failure_reason") else put("failure_reason", failureReason)
        }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id))
    }

    fun recent(limit: Int = 100): List<RecordingCatalogEntry> {
        require(limit in 1..500)
        val result = ArrayList<RecordingCatalogEntry>(minOf(limit, 100))
        readableDatabase.query(
            "recordings",
            arrayOf(
                "id","title","channel_id","state","started_epoch","stopped_epoch",
                "bytes_written","file_path","failure_reason","protected",
            ),
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
            val protectedCol = cursor.getColumnIndexOrThrow("protected")
            while (cursor.moveToNext()) {
                result += RecordingCatalogEntry(
                    id = cursor.getString(idCol),
                    title = cursor.getString(titleCol),
                    channelId = cursor.getString(channelCol),
                    state = cursor.getString(stateCol),
                    startedEpochSeconds = if (cursor.isNull(startedCol)) null else cursor.getLong(startedCol),
                    stoppedEpochSeconds = if (cursor.isNull(stoppedCol)) null else cursor.getLong(stoppedCol),
                    bytesWritten = cursor.getLong(bytesCol),
                    storageLocation = if (cursor.isNull(pathCol)) null else cursor.getString(pathCol),
                    failureReason = if (cursor.isNull(failureCol)) null else cursor.getString(failureCol),
                    isProtected = cursor.getInt(protectedCol) != 0,
                )
            }
        }
        return result
    }
}

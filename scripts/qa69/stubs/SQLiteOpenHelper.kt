package android.database.sqlite

import android.content.Context

/** Minimal helper stand-in: every instance owns one scratch in-memory database. */
open class SQLiteOpenHelper(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") name: String?,
    @Suppress("UNUSED_PARAMETER") factory: SQLiteDatabase.CursorFactory?,
    @Suppress("UNUSED_PARAMETER") version: Int,
) {
    private val database = SQLiteDatabase()
    val writableDatabase: SQLiteDatabase get() = database
    val readableDatabase: SQLiteDatabase get() = database

    @Suppress("UNUSED_PARAMETER")
    open fun onCreate(db: SQLiteDatabase) = Unit

    @Suppress("UNUSED_PARAMETER")
    open fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun close() = Unit
}

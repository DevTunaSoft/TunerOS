package android.database.sqlite

import android.content.Context

/** Bounded stand-in: opens one named database and calls onCreate exactly once. */
abstract class SQLiteOpenHelper(
    context: Context?,
    name: String?,
    factory: SQLiteDatabase.CursorFactory?,
    version: Int,
) {
    val databaseName: String? = name
    val databaseVersion: Int = version

    val writableDatabase: SQLiteDatabase
        get() = open()

    val readableDatabase: SQLiteDatabase
        get() = open()

    private fun open(): SQLiteDatabase {
        val db = SQLiteDatabase.open(databaseName)
        if (!db.initialized) {
            db.initialized = true
            onCreate(db)
        }
        return db
    }

    abstract fun onCreate(db: SQLiteDatabase)
    open fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    open fun close() = Unit
}

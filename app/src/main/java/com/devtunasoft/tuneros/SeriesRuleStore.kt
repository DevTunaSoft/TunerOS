package com.devtunasoft.tuneros

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.SeriesRules

/**
 * Series rules the guide keeps, one row per rule. The table lives in a database of its own, so the schedule database keeps
 * its shape. The row count is bounded by [SeriesRules.MAX_RULES], and the rule id is the primary key, so a rule added
 * twice is one row.
 */
internal class SeriesRuleStore(
    context: Context,
    databaseName: String = qaDatabaseName ?: "series_rules.db",
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {

    companion object {
        /** QA runs point this at a scratch database. Production leaves it unset. */
        @Volatile
        var qaDatabaseName: String? = null
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE series_rules(
                id TEXT PRIMARY KEY,
                title_key TEXT NOT NULL,
                channel_id TEXT,
                created_epoch INTEGER NOT NULL
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Every rule, oldest first. */
    fun all(): List<SeriesRule> = readableDatabase.query(
        "series_rules", arrayOf("title_key", "channel_id"), null, null, null, null, "created_epoch, id",
    ).use { cursor ->
        val rules = ArrayList<SeriesRule>(cursor.count)
        while (cursor.moveToNext()) {
            val channel = if (cursor.isNull(1)) null else cursor.getString(1)
            rules += SeriesRule(cursor.getString(0), channel)
        }
        rules
    }

    /** Whether [rule] is stored. */
    fun contains(rule: SeriesRule): Boolean =
        readableDatabase.query("series_rules", arrayOf("id"), "id = ?", arrayOf(rule.id), null, null, null).use {
            it.moveToFirst()
        }

    /** Adds [rule] unless it is stored already or the store is full. False when nothing was added. */
    fun add(rule: SeriesRule, nowEpochSeconds: Long): Boolean {
        if (DatabaseUtils.queryNumEntries(readableDatabase, "series_rules") >= SeriesRules.MAX_RULES) return false
        val values = ContentValues().apply {
            put("id", rule.id)
            put("title_key", rule.titleKey)
            if (rule.channelId == null) putNull("channel_id") else put("channel_id", rule.channelId)
            put("created_epoch", nowEpochSeconds)
        }
        return writableDatabase.insertWithOnConflict(
            "series_rules", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    /** Removes [rule]. False when it was not stored. */
    fun remove(rule: SeriesRule): Boolean =
        writableDatabase.delete("series_rules", "id = ?", arrayOf(rule.id)) > 0

    /**
     * Replaces every stored rule with [rules] as one operation. False, with no stored rules changed, when the list is
     * invalid or any write fails. Unlike a remove-all/add-each loop, a refused list cannot leave a partial restore behind.
     */
    fun replaceAll(rules: List<SeriesRule>, nowEpochSeconds: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val table = object : SeriesRuleTable {
                override fun clear(): Boolean {
                    db.delete("series_rules", null, null)
                    return true
                }
                override fun insert(rule: SeriesRule, now: Long): Boolean = add(rule, now)
            }
            val outcome = replaceSeriesRules(table, all(), rules, nowEpochSeconds)
            if (outcome is SeriesReplaceOutcome.Applied) db.setTransactionSuccessful()
            return outcome is SeriesReplaceOutcome.Applied
        } finally {
            db.endTransaction()
        }
    }
}

/** The storage surface a whole-list rule replacement needs, small enough for a plain-JVM test double. */
internal interface SeriesRuleTable {
    fun clear(): Boolean
    fun insert(rule: SeriesRule, nowEpochSeconds: Long): Boolean
}

/** Whether a whole-list replacement was applied, or why it was refused before anything was changed. */
internal sealed class SeriesReplaceOutcome {
    object Applied : SeriesReplaceOutcome()
    data class Rejected(val reason: String) : SeriesReplaceOutcome()
}

/**
 * Policy for replacing all stored series rules. [current] is the snapshot that must survive a refusal. The whole list is
 * checked before any write, and a failed insert is rolled back, so a caller can never end up with half a backup restored.
 * Reasons are fixed strings: no rule value is repeated into a diagnostic.
 */
internal fun replaceSeriesRules(
    table: SeriesRuleTable,
    current: List<SeriesRule>,
    incoming: List<SeriesRule>,
    nowEpochSeconds: Long,
    maxRules: Int = SeriesRules.MAX_RULES,
): SeriesReplaceOutcome {
    if (maxRules <= 0) return SeriesReplaceOutcome.Rejected("the rule bound is invalid")
    if (incoming.size > maxRules) return SeriesReplaceOutcome.Rejected("too many series rules")
    val ids = HashSet<String>(incoming.size)
    for (rule in incoming) {
        if (!ids.add(rule.id)) return SeriesReplaceOutcome.Rejected("a series rule is duplicated")
    }
    if (!table.clear()) return SeriesReplaceOutcome.Rejected("the stored rules could not be cleared")
    for (rule in incoming) {
        if (table.insert(rule, nowEpochSeconds)) continue
        table.clear()
        for (old in current) table.insert(old, nowEpochSeconds)
        return SeriesReplaceOutcome.Rejected("a series rule could not be saved")
    }
    return SeriesReplaceOutcome.Applied
}

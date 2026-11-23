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
}

/** The identity of one schedule row a series rule reserved, needed to cancel it cleanly. */
internal data class SeriesDerivedSchedule(val id: String, val secureSourceKey: String)

/** The rule-store surface cancellation needs, kept small so it has a plain-JVM test double. */
internal interface SeriesRuleRemovalSink {
    fun remove(rule: SeriesRule): Boolean
}

/** The schedule-store surface cancellation needs. Only exact rule-id matches are returned. */
internal interface SeriesScheduleCancellationSink {
    fun derivedFor(ruleId: String): List<SeriesDerivedSchedule>
    fun cancel(id: String, secureSourceKey: String)
}

internal data class SeriesCancellationOutcome(val ruleRemoved: Boolean, val schedulesCancelled: Int)

/**
 * Removes [rule] and every schedule row reserved for its exact id. Rows from manual requests or other rules are never
 * selected, even when title or channel matches. Idempotent: a second call removes nothing and returns false / zero,
 * including after a partially completed earlier cancellation.
 */
internal fun cancelSeriesRuleWithSchedules(
    rule: SeriesRule,
    rules: SeriesRuleRemovalSink,
    schedules: SeriesScheduleCancellationSink,
): SeriesCancellationOutcome {
    val ruleRemoved = rules.remove(rule)
    var schedulesCancelled = 0
    for (entry in schedules.derivedFor(rule.id)) {
        schedules.cancel(entry.id, entry.secureSourceKey)
        schedulesCancelled++
    }
    return SeriesCancellationOutcome(ruleRemoved, schedulesCancelled)
}

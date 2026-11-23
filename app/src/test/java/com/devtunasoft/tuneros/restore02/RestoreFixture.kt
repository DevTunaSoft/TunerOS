package com.devtunasoft.tuneros.restore02

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.devtunasoft.tuneros.BackupRestore
import com.devtunasoft.tuneros.FavoriteChannelStore
import com.devtunasoft.tuneros.GuideMappingStore
import com.devtunasoft.tuneros.ParentalControlsStore
import com.devtunasoft.tuneros.SeriesRuleStore
import com.devtunasoft.tuneros.core.BackupContents
import com.devtunasoft.tuneros.core.SeriesRule

/** Synthetic preferences; one map per synthetic preference name. No real app data is read or written. */
internal class FakeContext : Context() {
    private val stores = HashMap<String, FakePreferences>()

    override val applicationContext: Context get() = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        stores.getOrPut(name ?: "synthetic") { FakePreferences() }
}

internal class FakePreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getStringSet(key: String?, defValue: Set<String>?): Set<String>? {
        val raw = values[key] ?: return defValue
        @Suppress("UNCHECKED_CAST")
        return raw as? Set<String> ?: defValue
    }

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(this)

    private class Editor(private val prefs: FakePreferences) : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null && values != null) pending[key] = values.toSet()
            return this
        }

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null && value != null) pending[key] = value
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = REMOVED
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            for ((key, value) in pending) {
                if (value === REMOVED) prefs.values.remove(key) else prefs.values[key] = value
            }
            pending.clear()
        }

        private object REMOVED
    }
}

/** Real production stores over synthetic preferences and an in-memory synthetic series database. */
internal class RestoreFixture {
    val context = FakeContext()
    val favorites = FavoriteChannelStore(context)
    val mappings = GuideMappingStore(context)
    val series = SeriesRuleStore(context, DATABASE_NAME)
    val restrictions = ParentalControlsStore(context)
    val restore = BackupRestore(favorites, mappings, series, restrictions, nowEpochSeconds = { NOW })
    val seriesDatabase: SQLiteDatabase get() = SQLiteDatabase.opened.getValue(DATABASE_NAME)

    init {
        // Force the synthetic helper to open its table so fault knobs can be set before a restore.
        series.all()
    }

    fun contents(rules: List<SeriesRule>): BackupContents = BackupContents(
        favorites = setOf(FAVORITE),
        guideMappings = mapOf(CHANNEL to GUIDE),
        seriesRules = rules,
        restrictedGroups = setOf(GROUP),
        restrictedChannels = setOf(RESTRICTED_CHANNEL),
    )

    companion object {
        const val NOW = 1_700_000_000L
        const val DATABASE_NAME = "synthetic-restore02-series.db"
        const val FAVORITE = "synthetic-favorite"
        const val CHANNEL = "synthetic-channel"
        const val GUIDE = "synthetic-guide"
        const val GROUP = "synthetic-group"
        const val RESTRICTED_CHANNEL = "synthetic-restricted"

        fun rule(index: Int): SeriesRule = SeriesRule("synthetic title $index", CHANNEL)
    }
}

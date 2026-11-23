package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.SeriesRule

/**
 * Synthetic in-memory stand-ins for the non-owned stores BackupQa talks to. They exist only in the
 * JVM test source set; their names match production so BackupQa/BackupRestore compile unmodified.
 */
internal class FavoriteChannelStore(context: android.content.Context, prefsName: String = "qa") {
    private var value: Set<String> = emptySet()
    fun load(): Set<String> = value
    fun save(ids: Set<String>): Boolean { value = ids.toSet(); return true }
}

internal class GuideMappingStore(context: android.content.Context, prefsName: String = "qa") {
    private var value: Map<String, String> = emptyMap()
    fun load(): Map<String, String> = value
    fun save(mappings: Map<String, String>): Boolean { value = mappings.toMap(); return true }
}

internal class SeriesRuleStore(context: android.content.Context, databaseName: String = "qa") : AutoCloseable {
    private val rules = LinkedHashMap<String, SeriesRule>()
    fun all(): List<SeriesRule> = rules.values.toList()
    fun contains(rule: SeriesRule): Boolean = rules.containsKey(rule.id)
    fun add(rule: SeriesRule, nowEpochSeconds: Long): Boolean {
        if (rules.containsKey(rule.id)) return false
        rules[rule.id] = rule
        return true
    }
    fun remove(rule: SeriesRule): Boolean = rules.remove(rule.id) != null
    override fun close() = Unit
}

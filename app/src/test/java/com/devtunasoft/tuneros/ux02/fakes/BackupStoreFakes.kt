package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.SeriesRules

/**
 * Synthetic in-memory stand-ins for the three non-owned stores BackupRestore talks to.
 * They exist only in the JVM test source set and mirror the real methods' contract.
 */
internal class FavoriteChannelStore {
    private var value: Set<String> = emptySet()
    var failSave = false
    fun load(): Set<String> = value
    fun save(ids: Set<String>): Boolean {
        if (failSave) return false
        value = ids.toSet()
        return true
    }
}

internal class GuideMappingStore {
    private var value: Map<String, String> = emptyMap()
    var failSave = false
    fun load(): Map<String, String> = value
    fun save(mappings: Map<String, String>): Boolean {
        if (failSave) return false
        value = mappings.toMap()
        return true
    }
}

internal class SeriesRuleStore {
    private val rules = LinkedHashMap<String, SeriesRule>()
    fun all(): List<SeriesRule> = rules.values.toList()
    fun contains(rule: SeriesRule): Boolean = rules.containsKey(rule.id)
    fun add(rule: SeriesRule, nowEpochSeconds: Long): Boolean {
        if (rules.size >= SeriesRules.MAX_RULES) return false
        return rules.putIfAbsent(rule.id, rule) == null
    }
    fun remove(rule: SeriesRule): Boolean = rules.remove(rule.id) != null
}

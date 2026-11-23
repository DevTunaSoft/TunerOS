package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.BackupContents
import com.devtunasoft.tuneros.core.ParentalPolicy
import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.SeriesRules
import com.devtunasoft.tuneros.core.TunerBackup
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Exports and restores favorites, guide mappings, series rules and restricted channel groups as a [TunerBackup]. Nothing
 * else is written: no playlist or guide URL, no provider credential, no parental PIN and no recording. A restore reads and
 * checks the whole file before any store changes, so a refused file leaves every store as it was.
 */
internal class BackupRestore(
    private val favorites: FavoriteChannelStore,
    private val mappings: GuideMappingStore,
    private val series: SeriesRuleStore,
    private val restrictions: ParentalControlsStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    fun exportText(): String = TunerBackup.encode(
        BackupContents(
            favorites = favorites.load(),
            guideMappings = mappings.load(),
            seriesRules = series.all(),
            restrictedGroups = restrictions.restrictedGroups(),
            restrictedChannels = restrictions.restrictedChannels(),
        ),
    )

    /**
     * Replaces the favorites, the guide mappings, the series rules, the restricted groups and the restricted channels with
     * [contents].
     */
    fun apply(contents: BackupContents) {
        // A backup may hold up to TunerBackup.MAX_RECORDS rules, but the planner accepts only SeriesRules.MAX_RULES.
        // Check the whole rule list before any write: a refusal must leave every store as it was.
        val refusal = ruleRefusal(contents.seriesRules)
        if (refusal != null) throw IllegalStateException(refusal)
        // The restricted groups are checked before any store changes, so a refused backup leaves every store as it was.
        val restricted = ParentalPolicy.normalizeAll(contents.restrictedGroups)
        val channels = ParentalPolicy.normalizeAll(contents.restrictedChannels)
        check(favorites.save(contents.favorites)) { "the favorites could not be saved" }
        check(mappings.save(contents.guideMappings)) { "the guide mappings could not be saved" }
        replaceSeriesRules(contents.seriesRules, nowEpochSeconds())
        restrictions.replaceRestrictedGroups(restricted)
        restrictions.replaceRestrictedChannels(channels)
    }

    /**
     * The caller-side bounded replacement policy. When the store gains its own transactional replaceAll, this body can
     * become a single call to it and keep the same fixed refusal reason.
     */
    private fun ruleRefusal(rules: List<SeriesRule>): String? {
        if (rules.size > SeriesRules.MAX_RULES) return TOO_MANY_RULES
        val ids = HashSet<String>(rules.size)
        for (rule in rules) if (!ids.add(rule.id)) return DUPLICATE_RULE
        return null
    }

    /** Replaces the stored rules, restoring the previous list if any write or the final check fails. */
    private fun replaceSeriesRules(rules: List<SeriesRule>, now: Long) {
        val previous = series.all()
        for (rule in previous) series.remove(rule)
        for (rule in rules) {
            if (series.add(rule, now)) continue
            restoreSeriesRules(previous, now)
            throw IllegalStateException(RULES_NOT_SAVED)
        }
        if (series.all().toSet() != rules.toSet()) {
            restoreSeriesRules(previous, now)
            throw IllegalStateException(RULES_NOT_SAVED)
        }
    }

    private fun restoreSeriesRules(previous: List<SeriesRule>, now: Long) {
        for (rule in series.all()) series.remove(rule)
        for (rule in previous) series.add(rule, now)
    }

    companion object {
        /** Fixed refusal reasons: no rule value is ever repeated into a diagnostic. */
        private const val TOO_MANY_RULES = "the backup holds too many series rules"
        private const val DUPLICATE_RULE = "the backup holds a duplicate series rule"
        private const val RULES_NOT_SAVED = "the series rules could not be saved"

        /**
         * Reads at most one byte more than the backup limit, so an oversized file is refused without being read to its end.
         */
        fun readBounded(input: InputStream): String {
            val limit = TunerBackup.MAX_BYTES + 1
            val collected = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (collected.size() < limit) {
                val read = input.read(chunk, 0, minOf(chunk.size, limit - collected.size()))
                if (read < 0) break
                collected.write(chunk, 0, read)
            }
            require(collected.size() <= TunerBackup.MAX_BYTES) { "the backup is too large" }
            return String(collected.toByteArray(), Charsets.UTF_8)
        }
    }
}

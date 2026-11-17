package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.BackupContents
import com.devtunasoft.tuneros.core.TunerBackup
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Exports and restores favorites, guide mappings and series rules as a [TunerBackup]. Nothing else is written: no playlist or
 * guide URL, no provider credential, no parental PIN and no recording. A restore reads and checks the whole file before any
 * store changes, so a refused file leaves every store as it was.
 */
internal class BackupRestore(
    private val favorites: FavoriteChannelStore,
    private val mappings: GuideMappingStore,
    private val series: SeriesRuleStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    fun exportText(): String = TunerBackup.encode(
        BackupContents(
            favorites = favorites.load(),
            guideMappings = mappings.load(),
            seriesRules = series.all(),
            restrictedGroups = emptySet(),
        ),
    )

    /** Replaces the favorites, the guide mappings and the series rules with [contents]. */
    fun apply(contents: BackupContents) {
        check(favorites.save(contents.favorites)) { "the favorites could not be saved" }
        check(mappings.save(contents.guideMappings)) { "the guide mappings could not be saved" }
        val now = nowEpochSeconds()
        for (rule in series.all()) series.remove(rule)
        for (rule in contents.seriesRules) series.add(rule, now)
    }

    companion object {
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

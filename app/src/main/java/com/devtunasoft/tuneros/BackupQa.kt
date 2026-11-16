package com.devtunasoft.tuneros

import android.content.Context
import com.devtunasoft.tuneros.core.BackupContents
import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.TunerBackup
import java.io.File

/**
 * Debug-only backup check on scratch stores, never the real ones. It seeds scratch favorites, guide mappings and series
 * rules, exports them, and checks that the file is clean. It empties the stores, restores the export, and checks that the
 * stores match it again. Then it checks that a file holding a URL is refused and changes nothing. Each check writes one PASS
 * or FAIL line to the backup report.
 */
internal object BackupQa {
    private const val SCRATCH_PREFS = "tuneros-backup-qa"
    private const val SCRATCH_DATABASE = "tuneros-backup-qa.db"
    private const val NOW = 1_700_000_000L

    private val fixture = BackupContents(
        favorites = setOf("qa-one", "qa-two"),
        guideMappings = mapOf("qa-one" to "guide.qa.one"),
        seriesRules = listOf(SeriesRule("qa show", "qa-one"), SeriesRule("qa news", null)),
        restrictedGroups = emptySet(),
    )

    fun run(context: Context) {
        val report = File(context.filesDir, "tuneros-backup-report.txt")
        report.writeText("RUNNING\n")
        try {
            val favorites = FavoriteChannelStore(context, SCRATCH_PREFS)
            val mappings = GuideMappingStore(context, SCRATCH_PREFS)
            SeriesRuleStore(context, SCRATCH_DATABASE).use { series ->
                val restore = BackupRestore(favorites, mappings, series, nowEpochSeconds = { NOW })

                restore.apply(fixture)
                val text = restore.exportText()
                val lower = text.lowercase()
                val clean = text.startsWith(TunerBackup.HEADER + "\n") &&
                    !text.contains("://") && !lower.contains("password") && !lower.contains("token=")
                record(report, "qa-backup-export-clean", clean, "bytes=${text.toByteArray(Charsets.UTF_8).size}")

                restore.apply(BackupContents(emptySet(), emptyMap(), emptyList(), emptySet()))
                restore.apply(TunerBackup.decode(text))
                val restored = restore.exportText()
                record(
                    report,
                    "qa-backup-round-trip",
                    restored == text,
                    "favorites=${favorites.load().size} series=${series.all().size}",
                )

                val hostile = TunerBackup.HEADER + "\nfavorite\thttps://qa.example/list.m3u8\n"
                val refused = runCatching { restore.apply(TunerBackup.decode(hostile)) }
                    .exceptionOrNull() is IllegalArgumentException
                record(
                    report,
                    "qa-backup-refuses-url",
                    refused && restore.exportText() == text,
                    "refused=$refused",
                )
            }
        } catch (error: Exception) {
            record(report, "qa-backup-error", false, error.javaClass.simpleName)
        } finally {
            context.deleteSharedPreferences(SCRATCH_PREFS)
            context.deleteDatabase(SCRATCH_DATABASE)
        }
    }

    private fun record(report: File, checkId: String, passed: Boolean, detail: String) {
        report.appendText((if (passed) "PASS" else "FAIL") + "\t" + checkId + "\t" + detail + "\n")
    }
}

package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CORE-60: export must fail closed on data that import cannot restore. Import normalizes restricted
 * names and refuses one that normalizes to empty; export checked only the raw field and happily
 * produced a backup it could not read back. All names below are synthetic scratch data.
 */
class BackupExportImportSymmetryTest {
    private fun backup(
        groups: Set<String> = emptySet(),
        channels: Set<String> = emptySet(),
    ): BackupContents = BackupContents(
        favorites = emptySet(),
        guideMappings = emptyMap(),
        seriesRules = emptyList(),
        restrictedGroups = groups,
        restrictedChannels = channels,
    )

    @Test fun aWhitespaceOnlyRestrictedGroupIsRefusedAtExport() {
        assertFailsWith<IllegalArgumentException> {
            TunerBackup.encode(backup(groups = setOf("   ")))
        }
    }

    @Test fun aWhitespaceOnlyRestrictedChannelIsRefusedAtExport() {
        assertFailsWith<IllegalArgumentException> {
            TunerBackup.encode(backup(channels = setOf(" ")))
        }
    }

    @Test fun aNormalRestrictedBackupStillRoundTripsThroughImportNormalization() {
        val original = backup(groups = setOf(" Adult "), channels = setOf(" News Three "))
        val restored = TunerBackup.decode(TunerBackup.encode(original))
        assertEquals(setOf("adult"), restored.restrictedGroups)
        assertEquals(setOf("news three"), restored.restrictedChannels)
    }
}

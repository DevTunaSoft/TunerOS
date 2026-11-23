package com.devtunasoft.tuneros.ux02

import com.devtunasoft.tuneros.BackupRestore
import com.devtunasoft.tuneros.FavoriteChannelStore
import com.devtunasoft.tuneros.GuideMappingStore
import com.devtunasoft.tuneros.SeriesRuleStore
import com.devtunasoft.tuneros.ParentalControlsStore
import com.devtunasoft.tuneros.core.BackupContents
import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.SeriesRules
import com.devtunasoft.tuneros.core.TunerBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-02: a restore is all-or-nothing as far as restrictions and rule counts are concerned. */
class BackupRestoreRegressionTest {

    private class Fixture {
        val context = FakePreferencesContext()
        val favorites = FavoriteChannelStore()
        val mappings = GuideMappingStore()
        val series = SeriesRuleStore()
        val parental = ParentalControlsStore(context)
        val restore = BackupRestore(favorites, mappings, series, parental, nowEpochSeconds = { 1_700_000_000L })

        fun contents(rules: Int, channels: Set<String>) = BackupContents(
            favorites = setOf("fav-a", "fav-b"),
            guideMappings = mapOf("ch-a" to "guide-a"),
            seriesRules = (0 until rules).map { SeriesRule("show $it", "ch-a") },
            restrictedGroups = setOf("adults"),
            restrictedChannels = channels,
        )
    }

    @Test
    fun restoreOfRestrictedChannelsRoundTripsAndCancelChangesNothing() {
        val f = Fixture()
        val before = f.parental.restrictedGroups() to f.parental.restrictedChannels()
        val contents = f.contents(2, setOf("restricted-a", "restricted-b"))

        // A cancelled restore never calls apply. The untouched state must survive.
        val text = TunerBackup.encode(contents)
        assertEquals(before, f.parental.restrictedGroups() to f.parental.restrictedChannels())

        f.restore.apply(TunerBackup.decode(text))
        assertEquals(setOf("adults"), f.parental.restrictedGroups())
        assertEquals(setOf("restricted-a", "restricted-b"), f.parental.restrictedChannels())
        assertEquals(contents.seriesRules.toSet(), f.series.all().toSet())
        assertEquals(text, f.restore.exportText())
    }

    @Test
    fun oversizedRuleListIsRefusedBeforeAnyStoreChanges() {
        val f = Fixture()
        val result = runCatching {
            f.restore.apply(f.contents(SeriesRules.MAX_RULES + 1, setOf("restricted-a")))
        }
        assertTrue("An over-limit backup must be refused, got $result", result.exceptionOrNull() != null)
        assertEquals(emptySet<String>(), f.favorites.load())
        assertEquals(emptyMap<String, String>(), f.mappings.load())
        assertEquals(emptyList<SeriesRule>(), f.series.all())
        assertEquals(emptySet<String>(), f.parental.restrictedGroups())
        assertEquals(emptySet<String>(), f.parental.restrictedChannels())
    }

    @Test
    fun partialRestrictionWriteMustNotLeaveOnlyGroupsChanged() {
        val f = Fixture()
        f.parental.replaceRestrictedGroups(setOf("keep-group"))
        f.parental.replaceRestrictedChannels(setOf("keep-channel"))
        f.context.failOnNextStringSetPut(2)

        val result = runCatching {
            f.restore.apply(f.contents(1, setOf("new-channel")))
        }
        assertTrue("The injected write failure must surface", result.exceptionOrNull() != null)
        assertEquals(
            "Restrictions must be written as one unit when the channel write fails",
            setOf("keep-group"),
            f.parental.restrictedGroups(),
        )
        assertEquals(setOf("keep-channel"), f.parental.restrictedChannels())
    }

    @Test
    fun failedFavoriteSaveChangesNoRestrictions() {
        val f = Fixture()
        f.favorites.failSave = true
        val result = runCatching { f.restore.apply(f.contents(1, setOf("new-channel"))) }
        assertTrue("A failed favorite save must fail the restore", result.exceptionOrNull() != null)
        assertEquals(emptySet<String>(), f.parental.restrictedChannels())
    }
}

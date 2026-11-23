package com.devtunasoft.tuneros.restore02

import android.database.sqlite.SQLiteDatabase
import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.SeriesRules
import com.devtunasoft.tuneros.core.TunerBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RESTORE-02. The backup format accepts up to [TunerBackup.MAX_RECORDS] records, while the rule planner accepts only
 * [SeriesRules.MAX_RULES]. These tests exercise the real [com.devtunasoft.tuneros.BackupRestore.apply] against real
 * production stores backed by synthetic in-memory preferences and a synthetic in-memory rule table. All names are
 * invented; no real PIN, provider URL, credential, media file or device is used.
 */
class BackupRestoreSeriesTest {
    private lateinit var fixture: RestoreFixture

    @Before
    fun setUp() {
        SQLiteDatabase.opened.clear()
        fixture = RestoreFixture()
    }

    @Test
    fun overCapRuleBackupIsRefusedBeforeAnyStoreChanges() {
        val incoming = (1..(SeriesRules.MAX_RULES + 2)).map { RestoreFixture.rule(it) }
        val contents = TunerBackup.decode(TunerBackup.encode(fixture.contents(incoming)))

        val result = runCatching { fixture.restore.apply(contents) }

        assertEquals("a decoded backup over the rule cap must be refused", "the backup holds too many series rules", result.exceptionOrNull()?.message)
        assertEquals(emptySet<String>(), fixture.favorites.load())
        assertEquals(emptyMap<String, String>(), fixture.mappings.load())
        assertEquals(emptyList<SeriesRule>(), fixture.series.all())
        assertEquals(emptySet<String>(), fixture.restrictions.restrictedGroups())
        assertEquals(emptySet<String>(), fixture.restrictions.restrictedChannels())
    }

    @Test
    fun duplicateRuleIdsAreRefusedBeforeAnyStoreChanges() {
        val first = RestoreFixture.rule(1)
        val incoming = listOf(first, RestoreFixture.rule(2), first)
        val contents = TunerBackup.decode(TunerBackup.encode(fixture.contents(incoming)))

        val result = runCatching { fixture.restore.apply(contents) }

        assertEquals("duplicate ids must be refused", "the backup holds a duplicate series rule", result.exceptionOrNull()?.message)
        assertEquals(emptySet<String>(), fixture.favorites.load())
        assertEquals(emptyMap<String, String>(), fixture.mappings.load())
        assertEquals(emptyList<SeriesRule>(), fixture.series.all())
    }

    @Test
    fun failedRuleInsertFailsRestoreAndKeepsThePreviousRules() {
        val previous = listOf(RestoreFixture.rule(90), RestoreFixture.rule(91))
        previous.forEach { assertTrue(fixture.series.add(it, RestoreFixture.NOW)) }
        val storedBefore = fixture.series.all()
        fixture.seriesDatabase.failNextInsert = true

        val result = runCatching {
            fixture.restore.apply(fixture.contents(listOf(RestoreFixture.rule(1), RestoreFixture.rule(2))))
        }

        assertEquals("a refused insert must fail the restore", "the series rules could not be saved", result.exceptionOrNull()?.message)
        assertEquals("a half-restored rule list is data loss", storedBefore, fixture.series.all())
    }

    @Test
    fun aWriteThatReportsSuccessButStoresNothingFailsRestore() {
        fixture.seriesDatabase.dropInserts = true

        val result = runCatching {
            fixture.restore.apply(fixture.contents(listOf(RestoreFixture.rule(1), RestoreFixture.rule(2))))
        }

        assertEquals("post-restore contents must be verified", "the series rules could not be saved", result.exceptionOrNull()?.message)
        assertEquals(emptyList<SeriesRule>(), fixture.series.all())
    }

    @Test
    fun aBackupExactlyAtTheRuleCapIsApplied() {
        val incoming = (1..SeriesRules.MAX_RULES).map { RestoreFixture.rule(it) }

        fixture.restore.apply(fixture.contents(incoming))

        assertEquals(incoming.toSet(), fixture.series.all().toSet())
    }

    @Test
    fun refusalReasonsNeverRepeatARuleValue() {
        val incoming = (1..(SeriesRules.MAX_RULES + 1)).map { RestoreFixture.rule(it) }

        val message = runCatching { fixture.restore.apply(fixture.contents(incoming)) }
            .exceptionOrNull()?.message.orEmpty()

        assertTrue("a refusal must happen", message.isNotEmpty())
        assertTrue("no rule title or channel belongs in a diagnostic: $message", !message.contains("synthetic"))
    }

    @Test
    fun aValidRestoreStillAppliesEveryStore() {
        val incoming = (1..3).map { RestoreFixture.rule(it) }
        val contents = fixture.contents(incoming)

        fixture.restore.apply(contents)

        assertEquals(incoming.toSet(), fixture.series.all().toSet())
        assertEquals(setOf(RestoreFixture.FAVORITE), fixture.favorites.load())
        assertEquals(mapOf(RestoreFixture.CHANNEL to RestoreFixture.GUIDE), fixture.mappings.load())
        assertEquals(setOf(RestoreFixture.GROUP), fixture.restrictions.restrictedGroups())
        assertEquals(setOf(RestoreFixture.RESTRICTED_CHANNEL), fixture.restrictions.restrictedChannels())
        assertEquals(TunerBackup.encode(contents), fixture.restore.exportText())
    }
}

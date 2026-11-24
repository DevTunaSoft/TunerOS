package com.devtunasoft.tuneros.ntf06

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NTF-06 negative-contract regressions for reboot rearm, startup recovery and
 * the alarm -> foreground-service handoff. Properties asserted here already
 * hold on the audited base; dual-rearm idempotency (C2 DVR-03), upgrade rearm
 * (C3 REL-01) and teardown ownership (C18 DVR-05) are sibling-branch coverage
 * and are deliberately NOT duplicated. No android.* stubs are declared.
 */
class Ntf06RearmHandoffContractTest {
    private val src: String get() = Ntf06Sources.compact(Ntf06Sources.scheduler())

    @Test
    fun rearmUsesBoundedKeysetPaging() {
        assertTrue("rearm must page by keyset", src.contains("store.pendingAfter(now, after = after)"))
        assertTrue("the page cursor must advance", src.contains("after = batch.last()"))
        assertFalse("offset paging can skip rows when rows are concurrently inserted", src.contains("OFFSET"))
    }

    @Test
    fun startupRecoveryHasAHardEntryCap() {
        assertTrue(
            "startup recovery must validate its cap",
            src.contains("require(maxEntries in 1..PREPARATION_RECOVERY_LIMIT)"),
        )
        assertTrue("scanned counter must bound the loop", src.contains("while (scanned < maxEntries)"))
        assertTrue(
            "the page size must shrink to the remaining budget",
            src.contains("minOf(PREPARATION_PAGE_SIZE, maxEntries - scanned)"),
        )
    }

    @Test
    fun oneInvalidAlarmCannotBlockTheRestOfTheBatch() {
        assertTrue("per-entry isolation missing", src.contains("catch (error: Exception)"))
        assertTrue(src.contains("failed++"))
        assertTrue(src.contains("\"Alarm re-arm failed: \" + error.javaClass.simpleName"))
    }

    @Test
    fun foregroundHandoffCarriesScheduleIdentityAndWindow() {
        assertTrue(src.contains("context.startForegroundService(serviceIntent)"))
        assertTrue(src.contains("putExtra(DvrRecordingService.EXTRA_ID, entry.id)"))
        assertTrue(
            "the secure key is required so the service can claim exactly this row",
            src.contains("putExtra(DvrRecordingService.EXTRA_SCHEDULE_KEY, entry.secureSourceKey)"),
        )
        assertTrue(src.contains("putExtra(DvrRecordingService.EXTRA_END_EPOCH_SECONDS, entry.endEpochSeconds)"))
        assertTrue(src.contains("putExtra(DvrRecordingService.EXTRA_URL, source.url)"))
    }

    @Test
    fun dispatchSuccessDoesNotMarkStartedOrDeleteSecrets() {
        val success = src.indexOf(".onSuccess {")
        val failure = src.indexOf(".onFailure {")
        assertTrue("start dispatch must distinguish success from failure", success >= 0 && failure > success)
        val block = src.substring(success, failure)
        assertTrue(block.contains("DVR_SCHEDULED_SERVICE_REQUESTED"))
        assertFalse(
            "secrets belong to the service that actually accepted STARTED",
            block.contains("ScheduledRecordingSecrets.delete"),
        )
        assertFalse("dispatch is not acceptance", block.contains("markStarted"))
    }

    @Test
    fun onlyScheduledOpenWindowsMayStartRecording() {
        assertTrue(src.contains("if (entry.state != \"SCHEDULED\") return"))
        assertTrue(src.contains("if (entry.endEpochSeconds <= now)"))
        val method = src.indexOf("private fun startScheduled(")
        val stateGuard = src.indexOf("if (entry.state != \"SCHEDULED\") return", method)
        val dispatch = src.indexOf("context.startForegroundService(serviceIntent)", method)
        assertTrue("state guard must precede dispatch", method <= stateGuard && stateGuard < dispatch)
    }

    @Test
    fun bootAndPermissionChangesAreTheOnlyRearmTriggersInThisFile() {
        assertTrue(src.contains("action != Intent.ACTION_BOOT_COMPLETED"))
        assertTrue(src.contains("AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
        assertFalse(
            "MY_PACKAGE_REPLACED is C3 REL-01 in a separate receiver + manifest; do not duplicate here",
            src.contains("MY_PACKAGE_REPLACED"),
        )
    }

    @Test
    fun permissionDeniedBroadcastDoesNotDispatchRearm() {
        assertTrue(src.contains("val shouldRearm ="))
        assertTrue(
            "the rearm call must be gated by shouldRearm",
            src.contains("if (shouldRearm) RecordingScheduler.rearmPending(context.applicationContext)"),
        )
    }
}

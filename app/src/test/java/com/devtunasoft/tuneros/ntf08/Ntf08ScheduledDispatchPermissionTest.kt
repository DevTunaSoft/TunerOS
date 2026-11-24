package com.devtunasoft.tuneros.ntf08

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NTF-08 source contracts for the scheduled-dispatch foreground-service gate.
 * C21 PERM-07 guards the Activity "Record Now" path only; this suite covers the
 * receiver path that starts the foreground recorder after an alarm fires.
 * Fixtures are synthetic source text only: no device, no dialog, no permission
 * grant, no provider URL, credential, PIN or recording payload.
 */
class Ntf08ScheduledDispatchPermissionTest {
    private val raw: String get() = Ntf08Sources.scheduler()
    private val src: String get() = Ntf08Sources.compact(raw)

    private fun body(signature: String): String {
        assertTrue("missing production member: $signature", raw.contains(signature))
        return Ntf08Sources.compact(Ntf08Sources.member(raw, signature))
    }

    private fun startScheduled(): String =
        body("private fun startScheduled(context: Context, id: String)")

    @Test
    fun scheduledDispatchGatesForegroundStartOnNotificationGrant() {
        val body = startScheduled()
        val guard = "if (!RecordingScheduler.canStartForegroundRecording(context)) {"
        assertTrue("scheduled dispatch must check the notification grant", body.contains(guard))
        val guardAt = body.indexOf(guard)
        val dispatchAt = body.indexOf("context.startForegroundService(serviceIntent)")
        assertTrue("the foreground recorder must still be dispatched", dispatchAt >= 0)
        assertTrue("the grant gate must precede the foreground start", guardAt < dispatchAt)
        val denied = body.substring(guardAt, dispatchAt)
        assertTrue("denial must re-arm one bounded retry", denied.contains("RecordingScheduler.rescheduleDeniedDispatch(context, entry)"))
        assertTrue("denial must return before the FGS start", denied.contains("return"))
        assertFalse("denial must never start the service", denied.contains("startForegroundService("))
    }

    @Test
    fun grantCheckIsApiGatedAndReadsTheFrameworkGrantState() {
        val body = body("internal fun canStartForegroundRecording(context: Context): Boolean")
        assertTrue(
            "POST_NOTIFICATIONS is a runtime permission only from Android 13",
            body.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU"),
        )
        assertTrue(
            "the gate must read the real grant state, not a cached flag",
            body.contains("context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED"),
        )
        assertTrue("the gate must yield a boolean", body.contains("return "))
    }

    @Test
    fun denialPreservesTheScheduleAndItsSecretsForRearm() {
        val body = startScheduled()
        val guardAt = body.indexOf("if (!RecordingScheduler.canStartForegroundRecording(context)) {")
        val serviceAt = body.indexOf("val serviceIntent =", guardAt)
        assertTrue("intent construction must follow the gate", serviceAt > guardAt)
        val denied = body.substring(guardAt, serviceAt)
        assertFalse("a notification denial must not fail the recording", denied.contains("failPending("))
        assertFalse("a notification denial must not delete source secrets", denied.contains("ScheduledRecordingSecrets.delete"))
        assertFalse("a notification denial must not persist permission state", denied.contains("updateAlarmMode"))
    }

    @Test
    fun retryReusesTheExistingAlarmIdentityAndStaysBounded() {
        val body = body("internal fun rescheduleDeniedDispatch(context: Context, entry: RecordingScheduleEntry)")
        assertTrue("reuse the one stable alarm identity", body.contains("alarmIntent(context, entry.id)"))
        assertTrue("re-arm an exact alarm", body.contains("setExactAndAllowWhileIdle("))
        assertTrue("a bounded retry delay must exist", body.contains("DISPATCH_RETRY_DELAY_MS"))
        assertTrue("the retry must not outlive the recording window", body.contains("entry.endEpochSeconds * 1_000"))
        assertTrue("respect the exact-alarm grant", body.contains("canScheduleExactAlarms()"))
        assertTrue("a failed re-arm must not crash the receiver", body.contains("runCatching"))
        assertFalse("no unbounded repeating alarm", body.contains("setRepeating("))
        assertFalse("no mutable PendingIntent", body.contains("FLAG_MUTABLE"))
    }

    @Test
    fun noPermissionStateIsLoggedOrPromptedOnTheScheduledPath() {
        val signatures = listOf(
            "private fun startScheduled(context: Context, id: String)",
            "internal fun canStartForegroundRecording(context: Context): Boolean",
            "internal fun rescheduleDeniedDispatch(context: Context, entry: RecordingScheduleEntry)",
        )
        for (signature in signatures) {
            val body = body(signature)
            assertFalse("no logging in $signature", body.contains("Log.") || body.contains("println("))
            assertFalse("a receiver cannot prompt", body.contains("requestPermissions("))
            assertFalse("no persisted denial state in $signature", body.contains("PERMISSION_DENIED"))
            assertFalse(
                "no permission telemetry in $signature",
                Regex("OnDeviceSelfTest\\.observe\\([^)]*(NOTIFICATION|PERMISSION)[^)]*\\)").containsMatchIn(body),
            )
        }
    }

    @Test
    fun existingAlarmIdentityAndRearmContractSurvivesTheGate() {
        assertEquals("one alarm builder keeps arm and cancel in lockstep", 1, Regex("private fun alarmIntent\\(").findAll(src).count())
        assertEquals("one dispatch site must remain", 1, Regex("startForegroundService\\(").findAll(src).count())
        assertTrue(src.contains("markFailedIfScheduled(entry.id, entry.secureSourceKey, reason)"))
        assertTrue(src.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(src.contains("canScheduleExactAlarms()"))
        assertTrue(src.contains("pendingAfter(now, after = after)"))
        val qa = src.indexOf("if (id.startsWith(QA_ALARM_PREFIX))")
        val dispatch = src.indexOf("startScheduled(context.applicationContext, id)")
        assertTrue("QA alarms must stay isolated", qa >= 0 && dispatch > qa)
    }
}

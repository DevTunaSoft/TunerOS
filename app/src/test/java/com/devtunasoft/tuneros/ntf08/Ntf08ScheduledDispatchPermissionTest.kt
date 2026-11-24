package com.devtunasoft.tuneros.ntf08

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NTF-08-CI source contracts for the scheduled-dispatch foreground-service path.
 *
 * CI run 45308398889 / job 138158203374 (Android 35) failed with
 * "Scheduled recording check failed (full stream 47545 bytes, recorded none)"
 * and "FAIL qa-scheduled-recording Exact alarm starts a stored schedule;
 * recorder completes at its stop and credentials are deleted bytes=-1".
 * The receiver skipped startForegroundService when POST_NOTIFICATIONS was
 * denied. A foreground service can run with a hidden notification: the grant
 * is not a precondition for a capture. These contracts pin that behaviour.
 *
 * Fixtures are synthetic source text only: no device, no dialog, no grant,
 * no provider URL, credential, PIN or recording payload.
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

    private fun failedDispatchRetry(): String =
        body("internal fun rescheduleFailedDispatch(context: Context, entry: RecordingScheduleEntry): Boolean")

    @Test
    fun scheduledDispatchStartsForegroundServiceWithoutConsultingNotificationGrant() {
        val body = startScheduled()
        assertEquals(
            "exactly one foreground dispatch on the scheduled path",
            1,
            Regex("context\\.startForegroundService\\(serviceIntent\\)").findAll(body).count(),
        )
        val sourceAt = body.indexOf("ScheduledRecordingSecrets.load(")
        val serviceAt = body.indexOf("val serviceIntent =")
        val dispatchAt = body.indexOf("context.startForegroundService(serviceIntent)")
        assertTrue("the stored source must be loaded before dispatch", sourceAt >= 0)
        assertTrue("the service intent must be built before dispatch", serviceAt > sourceAt)
        assertTrue("the foreground service must actually be dispatched", dispatchAt > serviceAt)
        val beforeDispatch = body.substring(sourceAt, dispatchAt)
        assertFalse(
            "a denied notification grant must not skip the capture (CI 45308398889)",
            beforeDispatch.contains("canStartForegroundRecording"),
        )
        assertFalse(
            "a denied grant must not substitute a bounded retry for a real recording",
            beforeDispatch.contains("rescheduleDeniedDispatch"),
        )
        assertFalse("no permission state may be read", beforeDispatch.contains("POST_NOTIFICATIONS"))
        assertFalse(
            "no gate may return between intent construction and the FGS start",
            body.substring(serviceAt, dispatchAt).contains("return"),
        )
    }

    @Test
    fun schedulerSourceHasNoNotificationPermissionGate() {
        assertFalse(
            "POST_NOTIFICATIONS must never be read by the scheduled dispatcher",
            src.contains("POST_NOTIFICATIONS"),
        )
        assertFalse("no runtime grant check in the scheduler", src.contains("checkSelfPermission"))
        assertFalse("no grant-gated dispatch helper", src.contains("rescheduleDeniedDispatch"))
        assertFalse("no grant-gated mode persisted", src.contains("PERMISSION_GRANTED"))
    }

    @Test
    fun genuineStartFailureGetsBoundedRetryOnTheSameAlarmIdentity() {
        val failure = startScheduled().substringAfter("}.onFailure")
        assertTrue(
            "only a genuine start failure may use the bounded retry",
            failure.contains("rescheduleFailedDispatch(context, entry)"),
        )
        val body = failedDispatchRetry()
        assertTrue("reuse the one stable alarm identity", body.contains("alarmIntent(context, entry.id)"))
        assertTrue("re-arm an exact alarm", body.contains("setExactAndAllowWhileIdle("))
        assertTrue("a bounded retry delay must exist", body.contains("DISPATCH_RETRY_DELAY_MS"))
        assertTrue("the retry must not outlive the recording window", body.contains("entry.endEpochSeconds * 1_000"))
        assertTrue("respect the exact-alarm grant", body.contains("canScheduleExactAlarms()"))
        assertTrue("a failed re-arm must not crash the receiver", body.contains("runCatching"))
        assertTrue("the retry result decides whether to fail the row", body.contains("return "))
        assertFalse("no unbounded repeating alarm", body.contains("setRepeating("))
        assertFalse("no mutable PendingIntent", body.contains("FLAG_MUTABLE"))
    }

    @Test
    fun denialDoesNotFailPersistOrDeleteTheRecordingSource() {
        val body = startScheduled()
        assertFalse("the scheduled path must not delete source secrets itself", body.contains("ScheduledRecordingSecrets.delete"))
        assertFalse("the scheduled path must not persist alarm state", body.contains("updateAlarmMode"))
        assertFalse("no denial state may be stored", src.contains("PERMISSION_DENIED"))
        assertTrue(
            "failPending stays reserved for genuine preconditions and start failures",
            body.contains("failPending(context, entry, secrets, error.javaClass.simpleName)"),
        )
    }

    @Test
    fun noPermissionStateIsLoggedOrPromptedOnTheScheduledPath() {
        val signatures = listOf(
            "private fun startScheduled(context: Context, id: String)",
            "internal fun rescheduleFailedDispatch(context: Context, entry: RecordingScheduleEntry): Boolean",
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
    fun alarmIdentityRearmCancelAndQaIsolationSurviveTheFix() {
        assertEquals("one alarm builder keeps arm and cancel in lockstep", 1, Regex("private fun alarmIntent\\(").findAll(src).count())
        assertEquals("one dispatch site must remain", 1, Regex("startForegroundService\\(").findAll(src).count())
        assertTrue(src.contains("markFailedIfScheduled(entry.id, entry.secureSourceKey, reason)"))
        assertTrue(src.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(src.contains("canScheduleExactAlarms()"))
        assertTrue(src.contains("pendingAfter(now, after = after)"))
        val cancel = body("fun cancel(context: Context, id: String)")
        assertTrue("cancel must use the same alarm identity", cancel.contains("cancel(alarmIntent(context, id))"))
        assertTrue("rearm must use the same alarm identity", failedDispatchRetry().contains("alarmIntent(context, entry.id)"))
        val qa = src.indexOf("if (id.startsWith(QA_ALARM_PREFIX))")
        val dispatch = src.indexOf("startScheduled(context.applicationContext, id)")
        assertTrue("QA alarms must stay isolated", qa >= 0 && dispatch > qa)
    }
}

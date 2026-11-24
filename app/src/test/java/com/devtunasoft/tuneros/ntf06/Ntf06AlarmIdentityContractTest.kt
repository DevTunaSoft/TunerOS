package com.devtunasoft.tuneros.ntf06

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NTF-06 negative-contract regressions for alarm identity and receiver input
 * validation. Every assertion is a property the audited base already upholds;
 * the suite exists to keep a future edit from reintroducing a stale or
 * duplicate alarm. Fixtures are synthetic source text only - no device state,
 * URL, credential, media or android.* stub is involved.
 */
class Ntf06AlarmIdentityContractTest {
    private val src: String get() = Ntf06Sources.compact(Ntf06Sources.scheduler())

    @Test
    fun pendingIntentIdentityIsDerivedFromTheScheduleId() {
        assertTrue("data URI must disambiguate schedules: " + SRC_HINT, src.contains("data = Uri.Builder()"))
        assertTrue(src.contains(".scheme(\"tuneros\")"))
        assertTrue(src.contains(".authority(\"recording\")"))
        assertTrue(src.contains(".appendPath(id)"))
        assertTrue(src.contains("putExtra(RecordingAlarmReceiver.EXTRA_SCHEDULE_ID, id)"))
    }

    @Test
    fun cancelReusesTheSingleAlarmIntentBuilder() {
        assertTrue(
            "cancel must target the very identity that was armed",
            src.contains("context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))"),
        )
        assertTrue(
            "one builder is the only way schedule and cancel stay in lockstep",
            Regex("private fun alarmIntent\\(").findAll(src).count() == 1,
        )
    }

    @Test
    fun alarmsAreImmutableAndNeverRepeating() {
        assertTrue(
            "FLAG_IMMUTABLE is mandatory on S+ for alarm PendingIntents",
            src.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"),
        )
        assertFalse("a repeating alarm would accumulate unbounded live state", src.contains("setRepeating("))
        assertFalse("a mutable PendingIntent lets other apps rewrite the schedule id", src.contains("FLAG_MUTABLE"))
    }

    @Test
    fun receiverRejectsWrongActionAndBlankIdBeforeDoingWork() {
        val actionGuard = src.indexOf("if (intent?.action != ACTION_START_SCHEDULED) return")
        val idGuard = src.indexOf("getStringExtra(EXTRA_SCHEDULE_ID)?.takeIf { it.isNotBlank() } ?: return")
        val async = src.indexOf("val pending = goAsync()")
        assertTrue("action guard missing", actionGuard >= 0)
        assertTrue("blank-id guard missing", idGuard > actionGuard)
        assertTrue("async work must start only after both guards", async > idGuard)
    }

    @Test
    fun qaAlarmsAreIsolatedFromDurableScheduleState() {
        val qa = src.indexOf("if (id.startsWith(QA_ALARM_PREFIX))")
        val start = src.indexOf("startScheduled(context.applicationContext, id)")
        assertTrue("QA prefix branch missing", qa >= 0)
        assertTrue("QA branch must short-circuit before production dispatch", start > qa)
        val branch = src.substring(qa, start)
        assertTrue("QA delivery must be recorded", branch.contains("qaDeliveries[id] = idle"))
        assertFalse("QA alarms must never open the schedule store", branch.contains("RecordingScheduleStore"))
        assertFalse("QA alarms must never start the foreground recorder", branch.contains("startForegroundService"))
    }

    @Test
    fun staleAlarmFailureCannotClobberANewerReservation() {
        assertTrue(
            "failure must be conditional on the same id AND secure key",
            src.contains("it.markFailedIfScheduled(entry.id, entry.secureSourceKey, reason)"),
        )
        val body = src.substring(src.indexOf("private fun failPending("))
        assertFalse(
            "an unconditional markFailed would overwrite a newer/cancelled row",
            body.contains(".markFailed("),
        )
        assertFalse(
            "secrets may be deleted only after a matching row transitioned",
            body.indexOf("ScheduledRecordingSecrets.delete") < body.indexOf("val updated ="),
        )
    }

    private companion object {
        const val SRC_HINT = "RecordingScheduler.alarmIntent"
    }
}

package com.devtunasoft.tuneros.dvr03

import android.app.AlarmManager
import android.content.Context
import android.database.sqlite.Dvr03FakeSqlite
import com.devtunasoft.tuneros.RecordingScheduleEntry
import com.devtunasoft.tuneros.RecordingScheduleStore
import com.devtunasoft.tuneros.RecordingScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DVR-03 deterministic regression coverage for exact-alarm rearm after reboot
 * and durable schedule rows across a schema-version change.
 *
 * All ids, keys and titles are synthetic. The real production scheduler and
 * schedule store run against an in-memory SQLite stub; no device database,
 * provider URL, credential or recording is touched.
 */
class Dvr03RearmTest {
    companion object {
        private const val DB = "dvr03-rearm.db"

        private fun resetGuard() {
            // Reflective reset keeps production free of test-only API. On the
            // unfixed baseline the guard fields do not exist; the RED failure
            // must come from assertions, not from this helper.
            for (fieldName in listOf("rearmInProgress", "exactRearmDone")) {
                runCatching {
                    val field = RecordingScheduler::class.java.getDeclaredField(fieldName)
                    field.isAccessible = true
                    (field.get(null) as AtomicBoolean).set(false)
                }
            }
        }
    }

    @Before
    fun setUp() {
        resetGuard()
        Dvr03FakeSqlite.reset()
        RecordingScheduleStore.qaDatabaseName = DB
    }

    private fun contextWith(alarmManager: AlarmManager): Context = Context().apply {
        registerService(AlarmManager::class.java, alarmManager)
    }

    private fun entry(
        id: String,
        start: Long,
        end: Long,
        state: String = "SCHEDULED",
    ): RecordingScheduleEntry = RecordingScheduleEntry(
        id = id,
        title = "DVR-03 synthetic $id",
        channelId = "dvr03-channel",
        secureSourceKey = "dvr03-key-$id",
        startEpochSeconds = start,
        endEpochSeconds = end,
        state = state,
        exactAlarm = false,
        lastError = null,
    )

    private fun insert(vararg entries: RecordingScheduleEntry) {
        RecordingScheduleStore(Context()).use { store ->
            entries.forEach { assertTrue("insert ${it.id}", store.insertIfAbsent(it)) }
        }
    }

    @Test
    fun repeatedRearmDoesNotRefreshPastDueAlarmOrArmTwice() {
        val now = System.currentTimeMillis() / 1_000L
        // A past-due but still-open recording window: the boot receiver and
        // MainActivity both call rearmPending in this process.
        val pastDue = entry("dvr03-past-due", now - 600L, now + 7_200L)
        insert(pastDue)
        val alarms = AlarmManager()
        val context = contextWith(alarms)

        assertTrue("first rearm visits the pending row", RecordingScheduler.rearmPending(context))
        assertEquals(1, alarms.exactAlarms.size)
        val first = alarms.exactAlarms.single()
        assertEquals("dvr03-past-due", first.scheduleId)
        assertTrue("exact RTC_WAKEUP alarm", first.type == AlarmManager.RTC_WAKEUP)
        assertTrue("past-due trigger is clamped into the future", first.triggerAtMillis > now * 1_000L)

        Thread.sleep(20L)
        assertFalse("a second rearm in the same process must be a no-op",
            RecordingScheduler.rearmPending(context))

        assertEquals("one alarm per schedule, never a duplicate", 1, alarms.exactAlarms.size)
        assertEquals("no extra AlarmManager registration", 1, alarms.armCalls)
        assertEquals("the original trigger must not be pushed back",
            first.triggerAtMillis, alarms.exactAlarms.single().triggerAtMillis)
    }

    @Test
    fun overlappingBootReceiverAndActivityRearmArmsEachScheduleOnce() {
        val now = System.currentTimeMillis() / 1_000L
        insert(
            entry("dvr03-concurrent-a", now + 3_600L, now + 7_200L),
            entry("dvr03-concurrent-b", now + 3_700L, now + 7_200L),
        )
        val alarms = AlarmManager().apply { armPauseMillis = 40L }
        val context = contextWith(alarms)
        val gate = CountDownLatch(1)
        val started = CountDownLatch(2)
        val threads = (0 until 2).map {
            Thread {
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
                RecordingScheduler.rearmPending(context)
            }.apply { start() }
        }
        assertTrue("both rearm callers started", started.await(5, TimeUnit.SECONDS))
        gate.countDown()
        threads.forEach { it.join(5_000L); assertFalse("rearm thread must finish", it.isAlive) }

        assertEquals("one registration per stored schedule", 2, alarms.armCalls)
        assertEquals("no duplicate alarms", 2, alarms.exactAlarms.size)
        assertEquals(
            setOf("dvr03-concurrent-a", "dvr03-concurrent-b"),
            alarms.exactAlarms.map { it.scheduleId }.toSet(),
        )
    }

    @Test
    fun permissionGrantRearmStillRunsAfterAnInitiallyBlockedBootRearm() {
        val now = System.currentTimeMillis() / 1_000L
        insert(entry("dvr03-permission", now + 3_600L, now + 7_200L))
        val alarms = AlarmManager().apply { exactPermissionAllowed = false }
        val context = contextWith(alarms)

        assertTrue(RecordingScheduler.rearmPending(context))
        assertEquals("no alarm without exact permission", 0, alarms.armCalls)

        alarms.exactPermissionAllowed = true
        assertTrue("grant must still rearm pending rows", RecordingScheduler.rearmPending(context))
        assertEquals(1, alarms.armCalls)
        assertEquals("dvr03-permission", alarms.exactAlarms.single().scheduleId)
    }

    @Test
    fun schemaVersionChangePreservesEveryDurableScheduleRow() {
        val now = System.currentTimeMillis() / 1_000L
        val expected = (0 until 12).map { index ->
            entry(
                id = "dvr03-row-$index",
                start = now + 3_600L + index * 60L,
                end = now + 10_800L + index * 60L,
                state = if (index % 5 == 0) "STARTED" else "SCHEDULED",
            )
        }
        insert(*expected.toTypedArray())

        // Model an upgrade opening the same durable file at a new schema version.
        Dvr03FakeSqlite.markStoredVersion(DB, 0)
        val restored = RecordingScheduleStore(Context()).use { store ->
            expected.map { store.get(it.id) }
        }
        assertEquals(expected.size, restored.size)
        expected.forEachIndexed { index, original ->
            assertEquals("durable row ${original.id} must survive", original, restored[index])
        }
        assertEquals(1, Dvr03FakeSqlite.upgradeCalls)
        assertEquals(0 to 1, Dvr03FakeSqlite.lastUpgrade)

        val alarms = AlarmManager()
        val context = contextWith(alarms)
        assertTrue(RecordingScheduler.rearmPending(context))
        assertEquals(
            "surviving SCHEDULED rows are rearmed, other states are untouched",
            expected.filter { it.state == "SCHEDULED" }.map { it.id }.toSet(),
            alarms.exactAlarms.map { it.scheduleId }.toSet(),
        )
        assertEquals("one alarm per rearmed row", 9, alarms.armCalls)
    }

    @Test
    fun lateAndCancelledRowsAreNeverArmed() {
        val now = System.currentTimeMillis() / 1_000L
        val future = entry("dvr03-future", now + 3_600L, now + 7_200L)
        val ended = entry("dvr03-ended", now - 7_200L, now - 60L)
        val cancelled = entry("dvr03-cancelled", now + 3_600L, now + 7_200L, "CANCELLED")
        insert(future, ended, cancelled)
        val alarms = AlarmManager()
        val context = contextWith(alarms)

        assertTrue(RecordingScheduler.rearmPending(context))
        assertEquals(setOf("dvr03-future"), alarms.exactAlarms.map { it.scheduleId }.toSet())
        assertEquals(1, alarms.armCalls)
    }
}

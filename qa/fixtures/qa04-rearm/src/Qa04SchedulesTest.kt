package com.devtunasoft.tuneros

import android.app.AlarmManager
import android.content.Context
import android.database.sqlite.QaSqliteBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * QA-04 scheduled-recording durability coverage on the real production store
 * and scheduler. All rows, keys and titles are synthetic. The existing
 * emulator self-test only checks one rearmed row's persisted flag; these
 * assertions cover a 450+ row round trip, the actual AlarmManager register
 * calls for every stored future schedule, and durable rows after a cold
 * reopen that models an app upgrade.
 */
class Qa04SchedulesTest {
    companion object {
        private const val BULK_ROWS = 461
        private const val PAGE_LIMIT = 200
        // Fixed far-future epoch: far beyond any runner clock, so ordering and
        // end_epoch filtering never depend on the wall clock.
        private const val FAR_FUTURE_START = 4_000_000_000L

        private fun freshDatabase(tag: String): String {
            QaSqliteBridge.closeAll()
            OnDeviceSelfTest.observations.clear()
            val scratch = System.getProperty("qa04.scratch")
                ?: error("qa04.scratch system property is required")
            val name = "qa04-$tag.db"
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                java.io.File(scratch, name + suffix).delete()
            }
            RecordingScheduleStore.qaDatabaseName = name
            return name
        }

        private fun entry(
            index: Int,
            prefix: String = "qa04-bulk",
            start: Long = FAR_FUTURE_START + index * 60L,
            end: Long = start + 1_800L,
            state: String = "SCHEDULED",
            exact: Boolean = index % 2 == 0,
            error: String? = if (index % 17 == 0) "qa04-synthetic-error" else null,
        ): RecordingScheduleEntry = RecordingScheduleEntry(
            id = "$prefix-$index",
            title = "QA schedule $index",
            channelId = "qa04-channel-$index",
            secureSourceKey = "qa04-key-$index",
            startEpochSeconds = start,
            endEpochSeconds = end,
            state = state,
            exactAlarm = exact,
            lastError = error,
        )
    }

    @Test
    fun storeRoundTrip450PlusNoSilentRowLoss() {
        val database = freshDatabase("roundtrip")
        val expected = (0 until BULK_ROWS).map { entry(it) }
        val context = Context()
        RecordingScheduleStore(context).use { store ->
            expected.forEach { assertTrue("insert ${it.id}", store.insertIfAbsent(it)) }
        }
        assertTrue("scratch database $database was created", database.isNotBlank())

        QaSqliteBridge.resetQueryRows()
        val seen = LinkedHashMap<String, RecordingScheduleEntry>()
        var after: RecordingScheduleEntry? = null
        var pages = 0
        RecordingScheduleStore(context).use { store ->
            while (true) {
                val page = store.pendingAfter(0L, limit = PAGE_LIMIT, after = after)
                if (page.isEmpty()) break
                pages++
                assertTrue("page $pages is bounded", page.size <= PAGE_LIMIT)
                for (item in page) {
                    assertFalse("duplicate row ${item.id}", seen.containsKey(item.id))
                    seen[item.id] = item
                }
                after = page.last()
            }
        }
        assertEquals("no silent row loss across keyset pages", expected.size, seen.size)
        assertEquals("bounded page count", 3, pages)
        assertEquals(expected.map { it.id }.toSet(), seen.keys.toSet())
        expected.forEach { assertEquals("stored row ${it.id}", it, seen[it.id]) }
        assertTrue(
            "one query must never materialise more than $PAGE_LIMIT rows (was ${QaSqliteBridge.maxQueryRows})",
            QaSqliteBridge.maxQueryRows <= PAGE_LIMIT,
        )
        println("DETAIL\tqa04-store-round-trip\trows=${seen.size} pages=$pages maxQueryRows=${QaSqliteBridge.maxQueryRows}")

        // Negative and boundary cases from the same durable store.
        RecordingScheduleStore(context).use { store ->
            assertFalse("duplicate id must be ignored", store.insertIfAbsent(expected.first()))
            assertEquals(expected.first(), store.get(expected.first().id))
            assertNull("unknown id", store.get("qa04-no-such-row"))
            try {
                store.insertIfAbsent(entry(index = BULK_ROWS, end = FAR_FUTURE_START))
                fail("an end time before the start time must be rejected")
            } catch (expectedError: IllegalArgumentException) {
                assertNotNull(expectedError)
            }
        }
    }

    @Test
    fun startupRearmRegistersExactAlarmsForStoredFutureSchedules() {
        freshDatabase("rearm")
        val context = Context()
        val alarmManager = AlarmManager()
        context.registerService(AlarmManager::class.java, alarmManager)
        val now = System.currentTimeMillis() / 1_000L
        val future = (0 until 5).map { index ->
            entry(
                index = index,
                prefix = "qa04-rearm-future",
                start = now + 3_600L + index * 60L,
                end = now + 7_200L + index * 60L,
            )
        }
        val ended = entry(0, "qa04-rearm-ended", now - 7_200L, now - 60L)
        val cancelled = entry(1, "qa04-rearm-cancelled", now + 3_600L, now + 7_200L, "CANCELLED")
        val preparing = entry(2, "qa04-rearm-preparing", now + 3_600L, now + 7_200L, "PREPARING")

        RecordingScheduleStore(context).use { store ->
            (future + listOf(ended, cancelled, preparing)).forEach {
                assertTrue("insert ${it.id}", store.insertIfAbsent(it))
            }
        }

        QaSqliteBridge.resetQueryRows()
        assertTrue("at least one pending schedule was visited", RecordingScheduler.rearmPending(context))
        val actualIds = alarmManager.exactAlarms.map { it.scheduleId }
        assertEquals(
            "only stored future SCHEDULED rows get an exact alarm",
            future.map { it.id }.toSet(),
            actualIds.toSet(),
        )
        assertEquals("one exact alarm per future schedule", future.size, actualIds.size)
        for (expected in future) {
            val armed = alarmManager.exactAlarms.singleOrNull { it.scheduleId == expected.id }
                ?: throw AssertionError("no AlarmManager registration for ${expected.id}")
            assertEquals("alarm type for ${expected.id}", AlarmManager.RTC_WAKEUP, armed.type)
            assertEquals(
                "exact trigger for ${expected.id}",
                expected.startEpochSeconds * 1_000L,
                armed.triggerAtMillis,
            )
        }
        assertEquals("exact alarm telemetry", future.size.toLong(),
            OnDeviceSelfTest.observations["DVR_REARM_EXACT_COUNT"] ?: -1L)
        assertTrue("rearm reads pages, never the whole table (${QaSqliteBridge.maxQueryRows})",
            QaSqliteBridge.maxQueryRows <= PAGE_LIMIT)
        println("DETAIL\tqa04-startup-rearm\talarms=${alarmManager.exactAlarms.size} maxQueryRows=${QaSqliteBridge.maxQueryRows}")
        RecordingScheduleStore(context).use { store ->
            future.forEach {
                val row = store.get(it.id)
                assertNotNull("durable row ${it.id}", row)
                assertTrue("row ${it.id} remains scheduled", row!!.state == "SCHEDULED")
                assertTrue("row ${it.id} is marked exact", row.exactAlarm)
                assertNull("row ${it.id} has no alarm error", row.lastError)
            }
        }
        RecordingScheduler.cancel(context, future.first().id)
        assertFalse("cancel removes the exact alarm",
            alarmManager.exactAlarms.any { it.scheduleId == future.first().id })
    }

    @Test
    fun upgradePreservesDurableScheduleRows() {
        freshDatabase("upgrade")
        val context = Context()
        val alarmManager = AlarmManager()
        context.registerService(AlarmManager::class.java, alarmManager)
        val now = System.currentTimeMillis() / 1_000L
        val expected = (0 until BULK_ROWS).map { index ->
            when {
                index % 7 == 0 -> entry(index, "qa04-upgrade", now + 7_200L, now + 10_800L,
                    state = "STARTED", exact = true, error = null)
                index % 11 == 0 -> entry(index, "qa04-upgrade", now + 7_200L, now + 10_800L,
                    state = "FAILED", exact = false, error = "qa04-synthetic-failure")
                else -> entry(index, "qa04-upgrade", now + 7_200L, now + 10_800L)
            }
        }
        RecordingScheduleStore(context).use { store ->
            expected.forEach { assertTrue("insert ${it.id}", store.insertIfAbsent(it)) }
        }

        // Close every connection and open a fresh helper against the same file:
        // this is the durable state a replacement APK sees on its first launch.
        QaSqliteBridge.closeAll()
        val restored = RecordingScheduleStore(context).use { store ->
            expected.map { store.get(it.id) }
        }
        assertEquals("row count after upgrade", expected.size, restored.size)
        expected.forEachIndexed { index, original ->
            assertEquals("durable row ${original.id} after upgrade", original, restored[index])
        }
        assertEquals("schedule schema version survives upgrade", 1,
            QaSqliteBridge.request("version", "qa04-upgrade.db").toInt())
        println("DETAIL\tqa04-upgrade-rows\trows=${restored.size} schemaVersion=1")

        QaSqliteBridge.resetQueryRows()
        assertTrue("rearm still works after the upgrade", RecordingScheduler.rearmPending(context))
        val scheduled = expected.filter { it.state == "SCHEDULED" }.map { it.id }.toSet()
        assertEquals("all durable future rows rearmed after upgrade", scheduled,
            alarmManager.exactAlarms.map { it.scheduleId }.toSet())
    }
}

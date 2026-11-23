package com.devtunasoft.tuneros.seriescancel

import com.devtunasoft.tuneros.RecordingScheduleEntry
import com.devtunasoft.tuneros.SeriesCancellationOutcome
import com.devtunasoft.tuneros.SeriesDerivedSchedule
import com.devtunasoft.tuneros.SeriesRuleRemovalSink
import com.devtunasoft.tuneros.SeriesScheduleCancellationSink
import com.devtunasoft.tuneros.cancelSeriesRuleWithSchedules
import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.SeriesRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SERIES-01. Removing a series rule must also cancel the schedule rows that
 * [com.devtunasoft.tuneros.MainActivity.evaluateSeriesRules] derived from it.
 * Both stores are Android SQLite helpers, so the cross-database cancellation is
 * exercised here through the two small store surfaces it is built from; the
 * fakes are plain JVM objects and the assertions are deterministic.
 */
class SeriesCancellationTest {

    private class FakeRules(initial: Set<String>) : SeriesRuleRemovalSink {
        val remaining = initial.toMutableSet()
        var removeCalls = 0

        override fun remove(rule: SeriesRule): Boolean {
            removeCalls++
            return remaining.remove(rule.id)
        }
    }

    private class FakeSchedules(rows: List<Triple<String, String, String?>>) : SeriesScheduleCancellationSink {
        data class Row(val id: String, val secureSourceKey: String, val ruleId: String?)

        val rows = rows.map { Row(it.first, it.second, it.third) }.toMutableList()
        val cancelled = mutableListOf<String>()

        override fun derivedFor(ruleId: String): List<SeriesDerivedSchedule> =
            rows.filter { it.ruleId == ruleId }
                .map { SeriesDerivedSchedule(it.id, it.secureSourceKey) }

        override fun cancel(id: String, secureSourceKey: String) {
            cancelled += id
            rows.removeAll { it.id == id }
        }
    }

    private fun rule(title: String = "news at ten", channel: String? = "guide-one") =
        SeriesRules.ruleFor(title, channel)!!

    private val rule = rule()

    @Test
    fun removingRuleCancelsOnlyItsOwnDerivedScheduleRows() {
        val rules = FakeRules(setOf(rule.id))
        val schedules = FakeSchedules(
            listOf(
                Triple("chA-100", "scheduled_recording.chA-100", rule.id),
                Triple("chB-200", "scheduled_recording.chB-200", rule.id),
                Triple("chA-300", "scheduled_recording.chA-300", rule("other show", "guide-two").id),
                Triple("chA-400", "scheduled_recording.chA-400", null), // manual reservation
            ),
        )

        val outcome = cancelSeriesRuleWithSchedules(rule, rules, schedules)

        assertTrue(outcome.ruleRemoved)
        assertEquals(2, outcome.schedulesCancelled)
        assertEquals(listOf("chA-100", "chB-200"), schedules.cancelled)
        assertEquals(
            "another rule's row and a manual row must survive",
            listOf("chA-300", "chA-400"),
            schedules.rows.map { it.id },
        )
        assertTrue(rules.remaining.isEmpty())
    }

    @Test
    fun doubleCancelIsHarmless() {
        val rules = FakeRules(setOf(rule.id))
        val schedules = FakeSchedules(
            listOf(Triple("chA-100", "scheduled_recording.chA-100", rule.id)),
        )

        val first = cancelSeriesRuleWithSchedules(rule, rules, schedules)
        val second = cancelSeriesRuleWithSchedules(rule, rules, schedules)

        assertTrue(first.ruleRemoved)
        assertEquals(1, first.schedulesCancelled)
        assertFalse(second.ruleRemoved)
        assertEquals(0, second.schedulesCancelled)
        assertEquals(listOf("chA-100"), schedules.cancelled)
        assertTrue(schedules.rows.isEmpty())
    }

    @Test
    fun orphanedDerivedRowsAreCancelledEvenAfterRuleRowIsGone() {
        val rules = FakeRules(emptySet())
        val schedules = FakeSchedules(
            listOf(Triple("chA-100", "scheduled_recording.chA-100", rule.id)),
        )

        val outcome = cancelSeriesRuleWithSchedules(rule, rules, schedules)

        assertFalse(outcome.ruleRemoved)
        assertEquals(1, outcome.schedulesCancelled)
        assertTrue(schedules.rows.isEmpty())
    }

    @Test
    fun aChannelSpecificRuleDoesNotMatchAnotherRulesId() {
        val anyChannel = rule("news at ten", null)
        val onOne = rule("news at ten", "guide-one")
        val schedules = FakeSchedules(
            listOf(Triple("chA-100", "scheduled_recording.chA-100", onOne.id)),
        )

        val outcome = cancelSeriesRuleWithSchedules(anyChannel, FakeRules(setOf(anyChannel.id)), schedules)

        assertEquals(0, outcome.schedulesCancelled)
        assertEquals(listOf("chA-100"), schedules.rows.map { it.id })
    }

    @Test
    fun scheduleEntryCarriesTheRuleThatCreatedIt() {
        val entry = RecordingScheduleEntry(
            id = "chA-100",
            title = "News at Ten",
            channelId = "chA",
            secureSourceKey = "scheduled_recording.chA-100",
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
            state = "SCHEDULED",
            exactAlarm = false,
            lastError = null,
            seriesRuleId = rule.id,
        )
        assertEquals(rule.id, entry.seriesRuleId)
        assertEquals(null, RecordingScheduleEntry(
            id = "chA-400",
            title = "Manual",
            channelId = "chA",
            secureSourceKey = "scheduled_recording.chA-400",
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
            state = "SCHEDULED",
            exactAlarm = false,
            lastError = null,
        ).seriesRuleId)
    }
}

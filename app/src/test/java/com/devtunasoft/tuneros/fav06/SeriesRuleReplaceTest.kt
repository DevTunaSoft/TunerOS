package com.devtunasoft.tuneros.fav06

import com.devtunasoft.tuneros.SeriesReplaceOutcome
import com.devtunasoft.tuneros.SeriesRuleTable
import com.devtunasoft.tuneros.replaceSeriesRules
import com.devtunasoft.tuneros.core.SeriesRule
import com.devtunasoft.tuneros.core.SeriesRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FAV-06. A backup can hold up to 5,000 records, but the rule planner accepts at most [SeriesRules.MAX_RULES] rules.
 * Restoring with remove-all/add-each silently keeps the first 50 and drops the rest, and a failed insert leaves a
 * half-restored list. Every value here is synthetic; no real preferences, database, title or channel is used.
 */
internal class SeriesRuleReplaceTest {

    /** Mirrors SeriesRuleStore.add(): a duplicate or a full table returns false and stores nothing. */
    private class FakeTable(
        private val cap: Int = SeriesRules.MAX_RULES,
    ) : SeriesRuleTable {
        val rows = LinkedHashMap<String, SeriesRule>()
        var clears = 0
            private set
        var inserts = 0
            private set
        var failOnInsertOrdinal: Int? = null

        override fun clear(): Boolean {
            clears++
            rows.clear()
            return true
        }

        override fun insert(rule: SeriesRule, nowEpochSeconds: Long): Boolean {
            inserts++
            if (inserts == failOnInsertOrdinal) return false
            if (rows.size >= cap) return false
            rows[rule.id] = rule
            return true
        }

        fun seed(vararg rules: SeriesRule) {
            for (rule in rules) rows[rule.id] = rule
        }

        fun stored(): List<SeriesRule> = rows.values.toList()
    }

    private fun rule(index: Int, channel: String? = null): SeriesRule =
        SeriesRule("synthetic rule $index", channel)

    private fun rules(range: IntRange): List<SeriesRule> = range.map { rule(it) }

    @Test
    fun validReplacementAppliesExactly() {
        val table = FakeTable()
        val old = rule(90)
        table.seed(old, rule(91))
        val incoming = rules(1..3)

        val outcome = replaceSeriesRules(table, table.stored(), incoming, NOW)

        assertTrue(outcome is SeriesReplaceOutcome.Applied)
        assertEquals(incoming, table.stored())
        assertEquals(1, table.clears)
    }

    @Test
    fun anEmptyBackupClearsEveryOldRule() {
        val table = FakeTable()
        table.seed(rule(90), rule(91))

        val outcome = replaceSeriesRules(table, table.stored(), emptyList(), NOW)

        assertTrue(outcome is SeriesReplaceOutcome.Applied)
        assertTrue(table.stored().isEmpty())
    }

    @Test
    fun aListExactlyAtTheCapIsApplied() {
        val table = FakeTable()
        val incoming = rules(1..SeriesRules.MAX_RULES)

        val outcome = replaceSeriesRules(table, emptyList(), incoming, NOW)

        assertTrue(outcome is SeriesReplaceOutcome.Applied)
        assertEquals(incoming, table.stored())
    }

    @Test
    fun aListOverTheCapIsRefusedAndLeavesTheStoredRulesUntouched() {
        val table = FakeTable()
        val old = rules(80..82)
        table.seed(*old.toTypedArray())
        val incoming = rules(1..(SeriesRules.MAX_RULES + 3))

        val outcome = replaceSeriesRules(table, table.stored(), incoming, NOW)

        assertTrue("a list of ${incoming.size} rules must be refused, got $outcome", outcome is SeriesReplaceOutcome.Rejected)
        assertTrue("the refusal needs a reason", (outcome as SeriesReplaceOutcome.Rejected).reason.isNotBlank())
        assertEquals("no rule may be written when the list is refused", old, table.stored())
        assertEquals("refusal must not clear the old rules", 0, table.clears)
        assertEquals("refusal must not attempt a single insert", 0, table.inserts)
    }

    @Test
    fun duplicateIdsAreRefusedInsteadOfCollapsingTheList() {
        val table = FakeTable()
        val old = rule(70)
        table.seed(old)
        val first = rule(1, "synthetic-channel")
        assertEquals(first.id, rule(1, "synthetic-channel").id)
        val incoming = listOf(first, rule(2, "synthetic-channel"), rule(1, "synthetic-channel"))

        val outcome = replaceSeriesRules(table, table.stored(), incoming, NOW)

        assertTrue("duplicate ids must be refused, got $outcome", outcome is SeriesReplaceOutcome.Rejected)
        assertEquals(listOf(old), table.stored())
        assertEquals(0, table.clears)
    }

    @Test
    fun aFailedInsertRollsBackToThePreviousRules() {
        val table = FakeTable()
        val old = rules(60..61)
        table.seed(*old.toTypedArray())
        table.failOnInsertOrdinal = 3
        val incoming = rules(1..4)

        val outcome = replaceSeriesRules(table, table.stored(), incoming, NOW)

        assertTrue("a failed write must be refused, got $outcome", outcome is SeriesReplaceOutcome.Rejected)
        assertEquals("a partial restore is data loss", old, table.stored())
    }

    @Test
    fun anExplicitSmallerBoundIsHonoured() {
        val table = FakeTable(cap = SeriesRules.MAX_RULES)
        val incoming = rules(1..4)

        val outcome = replaceSeriesRules(table, emptyList(), incoming, NOW, maxRules = 3)

        assertTrue("the supplied bound must be enforced, got $outcome", outcome is SeriesReplaceOutcome.Rejected)
        assertTrue(table.stored().isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000L
    }
}

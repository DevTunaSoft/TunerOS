package com.devtunasoft.tuneros.ux02

import com.devtunasoft.tuneros.SeriesRuleStore
import com.devtunasoft.tuneros.core.SeriesRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-02 negative regression: cancelling one series rule deletes exactly its own
 * row and nothing else, so no rule row dangles after a cancel. The SQLite calls
 * are exercised against a synthetic in-memory database stub (no real DB file).
 */
class SeriesRuleCancelTest {

    @Test
    fun removingOneRuleDeletesOnlyThatRow() {
        val store = SeriesRuleStore(FakePreferencesContext())
        val news = SeriesRule("qa news", "qa-one")
        val anyChannel = SeriesRule("qa any", null)
        assertTrue(store.add(news, 100L))
        assertTrue(store.add(anyChannel, 101L))
        assertEquals(2, store.all().size)

        assertTrue(store.remove(news))
        assertFalse("The cancelled rule must be gone", store.contains(news))
        assertTrue("A different rule must survive", store.contains(anyChannel))
        assertEquals(listOf(anyChannel), store.all())
        assertEquals("id = ?", store.readableDatabase.lastDeleteWhere)
        assertEquals(listOf(news.id), store.readableDatabase.lastDeleteArgs)
    }

    @Test
    fun removingAnUnknownRuleDeletesNoRow() {
        val store = SeriesRuleStore(FakePreferencesContext())
        val kept = SeriesRule("qa kept", "qa-one")
        assertTrue(store.add(kept, 100L))

        assertFalse(store.remove(SeriesRule("qa never added", "qa-one")))
        assertEquals(listOf(kept), store.all())
        assertEquals(1, store.readableDatabase.rows.size)
        assertEquals(
            listOf("qa never added|qa-one"),
            store.readableDatabase.lastDeleteArgs,
        )
    }

    @Test
    fun aRuleCanBeAddedAgainAfterCancelWithoutADanglingRow() {
        val store = SeriesRuleStore(FakePreferencesContext())
        val rule = SeriesRule("qa repeat", "qa-one")
        assertTrue(store.add(rule, 100L))
        assertTrue(store.remove(rule))
        assertTrue(store.add(rule, 200L))
        assertEquals(listOf(rule), store.all())
        assertEquals(1, store.readableDatabase.rows.size)
    }
}

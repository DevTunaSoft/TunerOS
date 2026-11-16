package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesRulesTest {
    private val start = 1_000_000L

    private fun program(channel: String, title: String, startEpoch: Long = start, end: Long = start + 1_800) =
        GuideProgram(channelId = channel, title = title, startEpochSeconds = startEpoch, endEpochSeconds = end)

    private fun ruleOf(title: String, channel: String? = null): SeriesRule =
        SeriesRules.ruleFor(title, channel) ?: error("no rule for $title")

    @Test fun titlesNormaliseByCaseSpacingAndPunctuation() {
        assertEquals("the news", SeriesRules.titleKeyOf("  The  NEWS!! "))
        assertEquals("2 broke girls", SeriesRules.titleKeyOf("2 Broke Girls"))
        assertNull(SeriesRules.titleKeyOf("!!!"))
    }

    @Test fun aRuleWithoutAChannelMatchesTheTitleOnAnyChannel() {
        val rule = ruleOf("The News")
        assertTrue(SeriesRules.matches(rule, program("bbc", "the news!")))
        assertTrue(SeriesRules.matches(rule, program("itv", "THE  NEWS")))
    }

    @Test fun aRuleWithAChannelMatchesOnlyThatChannel() {
        val rule = ruleOf("The News", "bbc")
        assertTrue(SeriesRules.matches(rule, program("bbc", "The News")))
        assertFalse(SeriesRules.matches(rule, program("itv", "The News")))
    }

    @Test fun titlesMatchExactlyNotBySubstring() {
        val rule = ruleOf("The News")
        assertFalse(SeriesRules.matches(rule, program("bbc", "The News Tonight")))
        assertFalse(SeriesRules.matches(rule, program("bbc", "Morning The News")))
        assertFalse(SeriesRules.matches(rule, program("bbc", "The New")))
    }

    @Test fun aProgrammeThatHasStartedIsNotPlanned() {
        val rules = listOf(ruleOf("The News"))
        val started = program("bbc", "The News", startEpoch = start)
        val upcoming = program("bbc", "The News", startEpoch = start + 1)
        assertEquals(listOf(upcoming), SeriesRules.plan(rules, listOf(started, upcoming), nowEpochSeconds = start))
    }

    @Test fun aProgrammeMatchedByTwoRulesIsPlannedOnce() {
        val rules = listOf(ruleOf("The News"), ruleOf("The News", "bbc"))
        val once = program("bbc", "The News")
        assertEquals(listOf(once), SeriesRules.plan(rules, listOf(once), nowEpochSeconds = start - 1))
    }

    @Test fun aGuideEntryListedTwiceIsPlannedOnce() {
        val rules = listOf(ruleOf("The News"))
        val entry = program("bbc", "The News")
        assertEquals(listOf(entry), SeriesRules.plan(rules, listOf(entry, entry.copy()), nowEpochSeconds = start - 1))
    }

    @Test fun plannedProgrammesKeepTheGuideOrder() {
        val rules = listOf(ruleOf("The News"))
        val first = program("bbc", "The News", startEpoch = start + 10)
        val second = program("itv", "The News", startEpoch = start + 5)
        val other = program("bbc", "Other Show", startEpoch = start + 7)
        assertEquals(listOf(first, second), SeriesRules.plan(rules, listOf(first, other, second), nowEpochSeconds = start - 1))
    }

    @Test fun anEmptyRuleListPlansNothing() {
        assertTrue(SeriesRules.plan(emptyList(), listOf(program("bbc", "The News")), nowEpochSeconds = start - 1).isEmpty())
    }

    @Test fun ruleIdsAreStableForTheSameTitleAndChannelAndDistinctByChannel() {
        assertEquals(ruleOf("The News").id, ruleOf("the  news!").id)
        assertNotEquals(ruleOf("News", "a").id, ruleOf("News").id)
        assertNotEquals(ruleOf("News", "a").id, ruleOf("News", "b").id)
    }

    @Test fun aTitleWithNothingToMatchOnMakesNoRule() {
        assertNull(SeriesRules.ruleFor("...", "bbc"))
    }

    @Test fun thePlannerRefusesMoreRulesThanItsBudget() {
        val rules = List(SeriesRules.MAX_RULES + 1) { ruleOf("Show $it") }
        assertFailsWith<IllegalArgumentException> {
            SeriesRules.plan(rules, listOf(program("bbc", "Show 1")), nowEpochSeconds = start - 1)
        }
    }
}

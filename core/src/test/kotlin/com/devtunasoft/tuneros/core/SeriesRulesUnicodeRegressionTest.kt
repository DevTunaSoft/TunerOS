package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CORE-39: deterministic JVM coverage for Unicode title normalisation in SeriesRules.
 *
 * Audit of CORE-01..CORE-38 (branch list, branch commit messages, every branch's core test blobs)
 * found DST/date, boundary, property and diagnostics coverage for series plans, but no case with a
 * supplementary-plane (non-BMP) letter. The written contract is "letters and digits in lower case,
 * every other run of characters as one space": U+10400 is a letter and Java/Kotlin lowercase it to
 * U+10428. Iterating a Kotlin String yields UTF-16 chars, so a surrogate pair must be read as one code
 * point. All fixtures below are invented titles; no provider address, credential or media payload is used.
 */
class SeriesRulesUnicodeRegressionTest {
    private val capitalI = "\uD801\uDC00" // U+10400 DESERET CAPITAL LETTER LONG I
    private val smallI = "\uD801\uDC28"   // U+10428 DESERET SMALL LETTER LONG I

    @Test fun aSupplementaryLetterKeepsItsLowercaseCodePointInTheTitleKey() {
        assertEquals(smallI + "bc", SeriesRules.titleKeyOf(capitalI + "BC"))
    }

    @Test fun aTitleMadeOnlyOfSupplementaryLettersStillYieldsARule() {
        assertEquals(smallI, SeriesRules.titleKeyOf(capitalI))
        val rule = SeriesRules.ruleFor(capitalI, null)
        assertNotNull(rule)
        assertEquals(smallI, rule.titleKey)
    }

    @Test fun aSupplementaryLetterBetweenWordsKeepsTheSingleSpaceSeparators() {
        assertEquals("alpha " + smallI + " beta", SeriesRules.titleKeyOf("Alpha " + capitalI + " Beta"))
        assertEquals("alpha " + smallI + " beta", SeriesRules.titleKeyOf("Alpha--" + capitalI + "---Beta"))
    }

    @Test fun matchingNormalisesBothSidesThroughTheSameCodePointRule() {
        val rule = SeriesRules.ruleFor(capitalI + "News", null)
        assertNotNull(rule)
        assertTrue(
            SeriesRules.matches(rule, GuideProgram("chan", smallI + "news", 0L, 10L)),
        )
    }

    @Test fun planningKeepsDistinctChannelsAndDeduplicatesOnlyTheSameChannelAndStart() {
        val rule = SeriesRules.ruleFor(capitalI + "News", null)
        assertNotNull(rule)
        val first = GuideProgram("one", smallI + "news", 10L, 20L)
        val second = GuideProgram("two", smallI + "news", 10L, 20L)
        val duplicate = GuideProgram("one", capitalI + "news", 10L, 20L)
        assertEquals(listOf(first, second), SeriesRules.plan(listOf(rule), listOf(first, second, duplicate), 0L))
    }

    @Test fun ordinaryAndAccentedTitlesKeepTheirExistingNormalisation() {
        assertEquals("café hd", SeriesRules.titleKeyOf("Café HD"))
        assertEquals("24 7", SeriesRules.titleKeyOf("24/7"))
        val rule = SeriesRules.ruleFor("Café HD", null)
        assertNotNull(rule)
        assertTrue(SeriesRules.matches(rule, GuideProgram("chan", "CAFÉ  hd", 0L, 10L)))
    }
}

package com.devtunasoft.tuneros.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CORE-30: deterministic coverage of the Guide Doctor name-match index, the one guide unit with no
 * dedicated adversarial test in HEAD or on the sibling CORE branches. Existing GuideDoctorTest covers
 * exact/containment/blank/unscheduled/limit/name-tie behaviour; the CORE-19 branch covers limit bounds.
 * These cases pin the remaining documented contract: locale-independent case folding, the guide-ID key,
 * the 30-point shared-word score, punctuation/quality-word normalization and the guide-ID tie-break.
 */
class GuideDoctorNameMatchRegressionTest {
    private fun guideWith(vararg scheduled: String, names: Map<String, String>): GuideSnapshot =
        GuideSnapshot(
            programs = scheduled.map { GuideProgram(it, "Show", 0L, 3_600L) },
            channelNames = names,
        )

    @Test fun matchKeysIgnoreTheHostLocaleWhenFoldingCase() {
        val names = mapOf("itv" to "itv")
        val guide = guideWith("itv", names = names)
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val found = GuideDoctor.suggestions(
                Channel("c", "ITV HD", "https://example.org/c"), names, guide::hasSchedule,
            )
            assertEquals(listOf("itv" to 100), found.map { it.guideId to it.score })
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun aGuideIdKeyCanBeAnExactMatchWhenItsDisplayNameIsUnrelated() {
        val names = mapOf("bbc" to "Zulu Guide", "g-news" to "News")
        val guide = guideWith("bbc", "g-news", names = names)
        val found = GuideDoctor.suggestions(
            Channel("c", "BBC", "https://example.org/c"), names, guide::hasSchedule,
        )
        assertEquals(listOf("bbc" to 100), found.map { it.guideId to it.score })
    }

    @Test fun aSharedThreeLetterWordScoresBelowContainmentAndZeroScoresAreOmitted() {
        val names = linkedMapOf(
            "shared" to "Sports Central",
            "contained" to "Sports Weekly Live Plus",
            "none" to "Weather",
        )
        val guide = guideWith("shared", "contained", "none", names = names)
        val found = GuideDoctor.suggestions(
            Channel("c", "Sports Weekly Live", "https://example.org/c"), names, guide::hasSchedule,
        )
        assertEquals(listOf("contained" to 60, "shared" to 30), found.map { it.guideId to it.score })
    }

    @Test fun punctuationAndQualityWordsDoNotChangeTheMatchKey() {
        val names = mapOf("bbc1" to "B.B.C. One")
        val guide = guideWith("bbc1", names = names)
        val found = GuideDoctor.suggestions(
            Channel("c", "BBC-One: HD", "https://example.org/c"), names, guide::hasSchedule,
        )
        assertEquals(listOf("bbc1" to 100), found.map { it.guideId to it.score })
    }

    @Test fun equalScoresAndNamesTieBreakByGuideId() {
        val names = linkedMapOf("z-id" to "Same Name", "a-id" to "Same Name")
        val guide = guideWith("z-id", "a-id", names = names)
        val found = GuideDoctor.suggestions(
            Channel("c", "Same Name", "https://example.org/c"), names, guide::hasSchedule,
        )
        assertEquals(listOf("a-id", "z-id"), found.map { it.guideId })
    }
}

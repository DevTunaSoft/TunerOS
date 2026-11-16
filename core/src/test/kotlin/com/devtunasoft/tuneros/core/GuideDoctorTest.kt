package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GuideDoctorTest {
    private fun guideWith(vararg scheduled: String, names: Map<String, String>): GuideSnapshot =
        GuideSnapshot(
            programs = scheduled.map { GuideProgram(it, "Show", 0L, 3600L) },
            channelNames = names,
        )

    @Test fun emptyMappingsReturnTheSameList() {
        val channels = listOf(Channel("a", "Alpha", "https://example.org/a"))
        assertSame(channels, GuideDoctor.applyMappings(channels, emptyMap()))
    }

    @Test fun aMappingFollowsTheStableKeyNotTheChannelId() {
        val rotated = Channel("new-url-id", "Alpha", "https://example.org/a?token=new", stableKey = "k-alpha")
        val result = GuideDoctor.applyMappings(listOf(rotated), mapOf("k-alpha" to "g-alpha"))
        assertEquals("g-alpha", result.single().guideId)
    }

    @Test fun aMappingChangesOnlyTheMappedChannel() {
        val mapped = Channel("a", "Alpha", "https://example.org/a")
        val untouched = Channel("b", "Beta", "https://example.org/b", guideId = "g-beta")
        val result = GuideDoctor.applyMappings(listOf(mapped, untouched), mapOf("a" to "g-alpha"))
        assertEquals("g-alpha", result[0].guideId)
        assertSame(untouched, result[1])
    }

    @Test fun channelsWithoutScheduleAreThoseWithoutAGuideIdOrWithAnUnscheduledOne() {
        val guide = guideWith("g1", names = mapOf("g1" to "One", "g2" to "Two"))
        val channels = listOf(
            Channel("c1", "One", "https://example.org/1", guideId = "g1"),
            Channel("c2", "Nothing", "https://example.org/2"),
            Channel("c3", "Two", "https://example.org/3", guideId = "g2"),
        )
        assertEquals(listOf("c2", "c3"), GuideDoctor.channelsWithoutSchedule(channels, guide).map { it.id })
    }

    @Test fun withoutAGuideEveryChannelIsWithoutASchedule() {
        val channels = listOf(Channel("c1", "One", "https://example.org/1", guideId = "g1"))
        assertEquals(channels, GuideDoctor.channelsWithoutSchedule(channels, null))
    }

    @Test fun anExactNameRanksFirstAndIgnoresQualityWords() {
        val guide = guideWith("bbc1", "bbc2", names = mapOf("bbc1" to "BBC One", "bbc2" to "BBC Two"))
        val found = GuideDoctor.suggestions(
            Channel("c", "BBC One HD", "https://example.org/c"), guide.channelNames, guide::hasSchedule,
        )
        assertEquals("bbc1", found.first().guideId)
        assertEquals(100, found.first().score)
    }

    @Test fun containmentRanksBelowAnExactMatchAndNoMatchIsLeftOut() {
        val guide = guideWith(
            "itv", "encore", "sky",
            names = mapOf("itv" to "ITV", "encore" to "ITV Encore", "sky" to "Sky News"),
        )
        val found = GuideDoctor.suggestions(
            Channel("c", "ITV", "https://example.org/c"), guide.channelNames, guide::hasSchedule,
        )
        assertEquals(listOf("itv", "encore"), found.map { it.guideId })
        assertEquals(listOf(100, 60), found.map { it.score })
    }

    @Test fun unscheduledGuideChannelsAreNeverSuggested() {
        val guide = guideWith("g1", names = mapOf("g1" to "Alpha", "g2" to "Alpha"))
        val found = GuideDoctor.suggestions(
            Channel("c", "Alpha", "https://example.org/c"), guide.channelNames, guide::hasSchedule,
        )
        assertEquals(listOf("g1"), found.map { it.guideId })
    }

    @Test fun aBlankNameAndAnUnknownNameGiveNoSuggestions() {
        val guide = guideWith("g1", names = mapOf("g1" to "Alpha"))
        assertTrue(
            GuideDoctor.suggestions(Channel("c", "HD", "https://example.org/c"), guide.channelNames, guide::hasSchedule)
                .isEmpty(),
        )
        assertTrue(
            GuideDoctor.suggestions(Channel("c", "Omega", "https://example.org/c"), guide.channelNames, guide::hasSchedule)
                .isEmpty(),
        )
    }

    @Test fun theLimitIsRespectedAndTiesAreOrderedByName() {
        val names = (1..10).associate { "n$it" to "News $it" }
        val guide = guideWith(*names.keys.toTypedArray(), names = names)
        val found = GuideDoctor.suggestions(
            Channel("c", "News", "https://example.org/c"), guide.channelNames, guide::hasSchedule, limit = 3,
        )
        assertEquals(listOf("News 1", "News 10", "News 2"), found.map { it.displayName })
    }
}

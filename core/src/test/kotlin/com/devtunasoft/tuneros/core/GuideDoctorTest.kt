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

    @Test fun diagnosisCountsEachUnscheduledChannelUnderItsOwnReason() {
        val guide = guideWith("g1", names = mapOf("g1" to "One", "g2" to "Two"))
        val channels = listOf(
            Channel("c1", "One", "https://example.org/1", guideId = "g1"),
            Channel("c2", "Nothing", "https://example.org/2"),
            Channel("c3", "Two", "https://example.org/3", guideId = "g2"),
            Channel("c4", "Ghost", "https://example.org/4", guideId = "g3"),
        )
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), guide)
        assertEquals(4, diagnosis.channels)
        assertEquals(1, diagnosis.scheduled)
        assertEquals(1, diagnosis.noGuideId)
        assertEquals(1, diagnosis.noProgrammes, "g2 is declared with a name but has no programmes")
        assertEquals(1, diagnosis.guideIdNotDeclared, "g3 has no channel entry in the guide")
        assertEquals(3, diagnosis.withoutSchedule)
        assertEquals(0, diagnosis.savedMappings)
    }

    @Test fun savedMappingsThatCannotHelpAreCountedAgainstThePlaylistAndTheGuide() {
        val guide = guideWith("g1", names = mapOf("g1" to "One", "g2" to "Two"))
        val channels = listOf(
            Channel("new-url-id", "Alpha", "https://example.org/a?token=new", stableKey = "k-alpha"),
            Channel("b", "Beta", "https://example.org/b"),
        )
        val mappings = mapOf(
            "k-alpha" to "g1",
            "b" to "g2",
            "gone" to "g1",
        )
        val diagnosis = GuideDoctor.diagnose(channels, mappings, guide)
        assertEquals(1, diagnosis.scheduled, "k-alpha follows its stable key to a scheduled guide channel")
        assertEquals(1, diagnosis.noProgrammes, "b is mapped to g2, which has no programmes")
        assertEquals(3, diagnosis.savedMappings)
        assertEquals(1, diagnosis.mappingsForMissingChannels, "no playlist channel has the key gone")
        assertEquals(1, diagnosis.mappingsToUnscheduled, "only the mapping to g2 points at an unscheduled channel")
    }

    @Test fun aMappingToAGuideChannelThatIsNotDeclaredIsCountedAsUnscheduled() {
        val guide = guideWith("g1", names = mapOf("g1" to "One"))
        val channels = listOf(Channel("c1", "Ghost", "https://example.org/1"))
        val diagnosis = GuideDoctor.diagnose(channels, mapOf("c1" to "g404"), guide)
        assertEquals(1, diagnosis.guideIdNotDeclared)
        assertEquals(0, diagnosis.noGuideId)
        assertEquals(1, diagnosis.mappingsToUnscheduled)
        assertEquals(0, diagnosis.mappingsForMissingChannels)
    }

    @Test fun theDiagnosisAgreesWithTheChannelsWithoutSchedule() {
        val guide = guideWith("g1", names = mapOf("g1" to "One", "g2" to "Two"))
        val channels = listOf(
            Channel("c1", "One", "https://example.org/1", guideId = "g1"),
            Channel("c2", "Nothing", "https://example.org/2"),
            Channel("c3", "Two", "https://example.org/3", guideId = "g2"),
            Channel("c4", "Ghost", "https://example.org/4", guideId = "g3"),
            Channel("c5", "Five", "https://example.org/5"),
        )
        val mappingSets = listOf<Map<String, String>>(
            emptyMap(),
            mapOf("c2" to "g1", "c4" to "g2", "c5" to "g9"),
        )
        for (mappings in mappingSets) {
            val diagnosis = GuideDoctor.diagnose(channels, mappings, guide)
            val missing = GuideDoctor.channelsWithoutSchedule(GuideDoctor.applyMappings(channels, mappings), guide)
            assertEquals(missing.size, diagnosis.withoutSchedule, "mappings $mappings")
            assertEquals(channels.size, diagnosis.scheduled + diagnosis.withoutSchedule, "mappings $mappings")
        }
    }

    @Test fun anEmptyPlaylistAndGuideGiveAnEmptyDiagnosis() {
        val diagnosis = GuideDoctor.diagnose(emptyList(), emptyMap(), guideWith(names = emptyMap()))
        assertEquals(0, diagnosis.channels)
        assertEquals(0, diagnosis.withoutSchedule)
        assertEquals(0, diagnosis.mappingsForMissingChannels)
    }
}

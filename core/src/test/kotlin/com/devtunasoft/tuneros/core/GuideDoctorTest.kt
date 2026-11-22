package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GuideDoctorTest {
    /** The helper's programmes run from 0 to 3600, so 1000 is inside them and none of them has ended. */
    private val now = 1_000L

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
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), guide, now)
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
        val diagnosis = GuideDoctor.diagnose(channels, mappings, guide, now)
        assertEquals(1, diagnosis.scheduled, "k-alpha follows its stable key to a scheduled guide channel")
        assertEquals(1, diagnosis.noProgrammes, "b is mapped to g2, which has no programmes")
        assertEquals(3, diagnosis.savedMappings)
        assertEquals(1, diagnosis.mappingsForMissingChannels, "no playlist channel has the key gone")
        assertEquals(1, diagnosis.mappingsToUnscheduled, "only the mapping to g2 points at an unscheduled channel")
    }

    @Test fun aMappingToAGuideChannelThatIsNotDeclaredIsCountedAsUnscheduled() {
        val guide = guideWith("g1", names = mapOf("g1" to "One"))
        val channels = listOf(Channel("c1", "Ghost", "https://example.org/1"))
        val diagnosis = GuideDoctor.diagnose(channels, mapOf("c1" to "g404"), guide, now)
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
            val diagnosis = GuideDoctor.diagnose(channels, mappings, guide, now)
            val missing = GuideDoctor.channelsWithoutSchedule(GuideDoctor.applyMappings(channels, mappings), guide)
            assertEquals(missing.size, diagnosis.withoutSchedule, "mappings $mappings")
            assertEquals(channels.size, diagnosis.scheduled + diagnosis.withoutSchedule, "mappings $mappings")
        }
    }

    @Test fun anEmptyPlaylistAndGuideGiveAnEmptyDiagnosis() {
        val diagnosis = GuideDoctor.diagnose(emptyList(), emptyMap(), guideWith(names = emptyMap()), now)
        assertEquals(0, diagnosis.channels)
        assertEquals(0, diagnosis.withoutSchedule)
        assertEquals(0, diagnosis.mappingsForMissingChannels)
        assertEquals(0, diagnosis.staleChannels)
        assertNull(diagnosis.guideEndsAtEpochSeconds)
    }

    @Test fun staleChannelsAreScheduledChannelsWhoseProgrammesHaveAllEnded() {
        val guide = GuideSnapshot(
            programs = listOf(
                GuideProgram("g1", "Old", 0L, 3_600L),
                GuideProgram("g2", "Current", 9_000L, 20_000L),
            ),
            channelNames = mapOf("g1" to "One", "g2" to "Two"),
        )
        val channels = listOf(
            Channel("c1", "One", "https://example.org/1", guideId = "g1"),
            Channel("c2", "Two", "https://example.org/2", guideId = "g2"),
            Channel("c3", "Nothing", "https://example.org/3"),
        )
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), guide, nowEpochSeconds = 10_000L)
        assertEquals(2, diagnosis.scheduled)
        assertEquals(1, diagnosis.staleChannels, "g1 ended at 3600, before 10000; g2 is still running")
        assertEquals(20_000L, diagnosis.guideEndsAtEpochSeconds)
    }

    @Test fun aProgrammeEndingExactlyNowCountsAsEnded() {
        val guide = GuideSnapshot(
            programs = listOf(GuideProgram("g1", "Ends now", 0L, 10_000L)),
            channelNames = mapOf("g1" to "One"),
        )
        val channels = listOf(Channel("c1", "One", "https://example.org/1", guideId = "g1"))
        assertEquals(1, GuideDoctor.diagnose(channels, emptyMap(), guide, nowEpochSeconds = 10_000L).staleChannels,
            "a programme that ends at the current second has ended")
        assertEquals(0, GuideDoctor.diagnose(channels, emptyMap(), guide, nowEpochSeconds = 9_999L).staleChannels,
            "one second earlier it is still running")
    }

    @Test fun aMappedChannelIsJudgedByTheGuideChannelItIsMappedTo() {
        val guide = GuideSnapshot(
            programs = listOf(GuideProgram("g1", "Old", 0L, 3_600L)),
            channelNames = mapOf("g1" to "One"),
        )
        val channel = Channel("c1", "Alpha", "https://example.org/1")
        val mapped = GuideDoctor.diagnose(listOf(channel), mapOf("c1" to "g1"), guide, nowEpochSeconds = 10_000L)
        assertEquals(1, mapped.scheduled)
        assertEquals(1, mapped.staleChannels)
        assertEquals(0, GuideDoctor.diagnose(listOf(channel), emptyMap(), guide, 10_000L).scheduled)
    }

    @Test fun latestEndIsTheLargestEndWhetherOrNotProgrammesOverlap() {
        val nonoverlapping = GuideSnapshot(
            programs = listOf(GuideProgram("a", "One", 0L, 100L), GuideProgram("a", "Two", 100L, 300L)),
            channelNames = emptyMap(),
        )
        assertEquals(300L, nonoverlapping.latestEnd("a"))
        val overlapping = GuideSnapshot(
            programs = listOf(
                GuideProgram("b", "Long", 0L, 500L),
                GuideProgram("b", "Short", 10L, 50L),
                GuideProgram("b", "Next", 400L, 450L),
            ),
            channelNames = emptyMap(),
        )
        assertEquals(500L, overlapping.latestEnd("b"), "a long programme ends after the later ones")
        assertNull(overlapping.latestEnd("unknown"))
    }

    @Test fun theSummaryHoldsCountsAndTheGuideEndButNoNamesOrUrls() {
        val guide = GuideSnapshot(
            programs = listOf(GuideProgram("g1", "Old", 0L, 3_600L)),
            channelNames = mapOf("g1" to "Secret Name"),
        )
        val channels = listOf(Channel("c1", "Secret Name", "https://example.org/private?token=abc", guideId = "g1"))
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), guide, 10_000L)
        val text = GuideDoctor.summaryText(
            diagnosis,
            listOf("1 programmes lacked timezone: assumed UTC", "see https://example.org/x for details"),
            10_000L,
        )
        assertTrue(text.contains("checked at: 1970-01-01T02:46:40Z"), text)
        assertTrue(text.contains("channels: 1"), text)
        assertTrue(text.contains("scheduled but ended: 1"), text)
        assertTrue(text.contains("guide ends: 1970-01-01T01:00:00Z"), text)
        assertTrue(text.contains("- 1 programmes lacked timezone: assumed UTC"), text)
        assertFalse(text.contains("Secret Name"), text)
        assertFalse(text.contains("example.org"), text)
        assertFalse(text.contains("token"), text)
    }

    @Test fun aGuideWithoutProgrammesSaysSoAndOmitsTheWarningsLine() {
        val empty = GuideSnapshot(emptyList(), emptyMap())
        val text = GuideDoctor.summaryText(GuideDoctor.diagnose(emptyList(), emptyMap(), empty, 0L), emptyList(), 0L)
        assertTrue(text.contains("guide ends: no programmes"), text)
        assertFalse(text.contains("guide warnings:"), text)
    }
}

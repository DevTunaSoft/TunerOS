package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CORE-51: a blank or whitespace-only guide ID means "no guide ID". The playlist parser stores an empty
 * `tvg-id` as an empty string, and a malformed XMLTV file can declare a programme with an empty channel ID.
 * Guide Doctor must not classify a channel with no usable guide ID as scheduled because of that malformed
 * guide row, nor as a guide ID that is absent from the guide.
 *
 * All names and addresses below are synthetic scratch data.
 */
class GuideDoctorBlankGuideIdRegressionTest {
    private fun scratchChannel(id: String, guideId: String?): Channel = Channel(
        id = id,
        name = "Scratch $id",
        streamUrl = "https://scratch.invalid/guide-doctor/$id.ts",
        guideId = guideId,
    )

    private fun guideWithBlankChannel(): GuideSnapshot = GuideSnapshot(
        programs = listOf(
            GuideProgram("", "Malformed blank channel", 1_000L, 2_000L),
            GuideProgram("scratch-good", "Scratch good", 1_000L, 2_000L),
        ),
        channelNames = mapOf("" to "Malformed blank channel", "scratch-good" to "Scratch good"),
    ).also { it.prepareIndex() }

    @Test fun blankWhitespaceAndAbsentGuideIdsAllCountAsNoGuideId() {
        val channels = listOf(
            scratchChannel("blank", ""),
            scratchChannel("spaces", "   "),
            scratchChannel("absent", null),
        )
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), guideWithBlankChannel(), 1_500L)
        assertEquals(3, diagnosis.noGuideId)
        assertEquals(0, diagnosis.guideIdNotDeclared)
        assertEquals(0, diagnosis.noProgrammes)
        assertEquals(0, diagnosis.scheduled)
        assertEquals(3, diagnosis.withoutSchedule)
    }

    @Test fun aBlankGuideIdNeverBecomesScheduledThroughAMalformedBlankGuideChannel() {
        val diagnosis = GuideDoctor.diagnose(
            listOf(scratchChannel("blank", "")),
            emptyMap(),
            guideWithBlankChannel(),
            1_500L,
        )
        assertEquals(0, diagnosis.scheduled)
        assertEquals(1, diagnosis.noGuideId)
    }

    @Test fun aBlankSavedMappingIsCountedAsNoGuideId() {
        val channel = scratchChannel("mapped", "scratch-good")
        val diagnosis = GuideDoctor.diagnose(
            listOf(channel),
            mapOf(channel.storageKey to ""),
            guideWithBlankChannel(),
            1_500L,
        )
        assertEquals(1, diagnosis.noGuideId)
        assertEquals(0, diagnosis.guideIdNotDeclared)
        assertEquals(0, diagnosis.scheduled)
    }

    @Test fun aGenuinelyAbsentGuideIdRemainsUndeclared() {
        val diagnosis = GuideDoctor.diagnose(
            listOf(scratchChannel("absent", "not-in-the-guide")),
            emptyMap(),
            guideWithBlankChannel(),
            1_500L,
        )
        assertEquals(0, diagnosis.noGuideId)
        assertEquals(1, diagnosis.guideIdNotDeclared)
    }

    @Test fun diagnosisAndChannelsWithoutScheduleAgreeForBlankIds() {
        val channels = listOf(scratchChannel("blank", ""), scratchChannel("spaces", "  "))
        val guide = guideWithBlankChannel()
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), guide, 1_500L)
        assertEquals(GuideDoctor.channelsWithoutSchedule(channels, guide).size, diagnosis.withoutSchedule)
        assertEquals(2, diagnosis.withoutSchedule)
    }
}

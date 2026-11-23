package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CORE-43: deterministic coverage of HLS master-playlist external rendition aggregation.
 *
 * The existing HLS tests exercise one ordinary EXT-X-MEDIA audio/video pair; no CORE
 * slice has asserted the group cap boundary, repeated group IDs, tag/attribute case,
 * quoted punctuation, in-band (URI-less) renditions or canary-safe refusals. All
 * fixtures are synthetic and bounded by the parser's own line/count limits.
 */
class HlsRenditionGroupAdversarialTest {
    private val canary = "SYNTHETIC-CANARY-7481.invalid"

    private fun master(renditions: List<String>, withVariant: Boolean = true): String = buildString {
        append("#EXTM3U\n")
        renditions.forEach { append(it).append('\n') }
        if (withVariant) {
            append("#EXT-X-STREAM-INF:BANDWIDTH=1000000\n")
            append("variant/synthetic.m3u8\n")
        }
    }

    private fun rendition(type: String, group: String, uri: String = "uri/$group"): String =
        "#EXT-X-MEDIA:TYPE=$type,GROUP-ID=\"$group\",NAME=\"$group\",URI=\"$uri\""

    private fun parseMaster(text: String): HlsMasterPlaylist =
        HlsPlaylistParserCore.parse(text.reader()) as HlsMasterPlaylist

    @Test fun lowerCaseRenditionTypesAreGroupedByType() {
        val playlist = parseMaster(
            master(
                listOf(
                    rendition("audio", "aud"),
                    "#EXT-X-MEDIA:TYPE=video,GROUP-ID=\"cam\",NAME=\"Main\",URI=\"uri/cam\"",
                ),
            ),
        )
        assertEquals(setOf("aud"), playlist.externalAudioGroups)
        assertEquals(setOf("cam"), playlist.externalVideoGroups)
    }

    @Test fun repeatedGroupIdsAreStoredOncePerType() {
        val playlist = parseMaster(
            master(
                listOf(
                    rendition("AUDIO", "aud"),
                    rendition("AUDIO", "aud", uri = "uri/second"),
                    rendition("VIDEO", "cam"),
                ),
            ),
        )
        assertEquals(setOf("aud"), playlist.externalAudioGroups)
        assertEquals(setOf("cam"), playlist.externalVideoGroups)
        assertEquals(1, playlist.variants.size)
    }

    @Test fun quotedPunctuationAndEqualsSignsStayInsideTheGroupId() {
        val group = "a,b=c"
        val playlist = parseMaster(master(listOf(rendition("AUDIO", group))))
        assertEquals(setOf(group), playlist.externalAudioGroups)
    }

    @Test fun aRenditionWithoutAnExternalUriIsNotRecorded() {
        val playlist = parseMaster(
            master(
                listOf(
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"inband\",NAME=\"English\"",
                    rendition("VIDEO", "cam"),
                ),
            ),
        )
        assertEquals(emptySet<String>(), playlist.externalAudioGroups)
        assertEquals(setOf("cam"), playlist.externalVideoGroups)
    }

    @Test fun theGroupCapAcceptsTheLastSlotAndRefusesOneMoreGroup() {
        val limit = HlsPlaylistParserCore.MAX_EXTERNAL_RENDITION_GROUPS
        val atCap = parseMaster(master((0 until limit).map { rendition("AUDIO", "g$it") }))
        assertEquals(limit, atCap.externalAudioGroups.size)

        val overCap = master((0..limit).map { rendition("AUDIO", "g$it") })
        val error = assertFailsWith<IllegalArgumentException> { parseMaster(overCap) }
        assertEquals("HLS master exceeds $limit external rendition groups", error.message)
    }

    @Test fun aGroupAtTheCapCanStillBeRepeatedWithoutAFalseRefusal() {
        val limit = HlsPlaylistParserCore.MAX_EXTERNAL_RENDITION_GROUPS
        val groups = (0 until limit).map { rendition("AUDIO", "g$it") }.toMutableList()
        groups += rendition("AUDIO", "g0", uri = "uri/duplicate")
        val playlist = parseMaster(master(groups))
        assertEquals(limit, playlist.externalAudioGroups.size)
    }

    @Test fun aCanaryGroupIdIsNotEchoedByTheCapRefusal() {
        val limit = HlsPlaylistParserCore.MAX_EXTERNAL_RENDITION_GROUPS
        val groups = (0 until limit).map { rendition("AUDIO", "g$it") } + rendition("AUDIO", canary)
        val error = assertFailsWith<IllegalArgumentException> { parseMaster(master(groups)) }
        assertTrue(error.message?.contains(canary) != true, "refusal must not echo a provider value")
    }

    @Test fun lowerCaseRenditionTagsStillRejectMixingWithMediaSegments() {
        val text = "#EXTM3U\n#ext-x-media:TYPE=AUDIO,GROUP-ID=\"aud\",URI=\"u\"\n#EXTINF:4,\nsynthetic.ts\n"
        assertFailsWith<IllegalArgumentException> { HlsPlaylistParserCore.parse(text.reader()) }
    }
}

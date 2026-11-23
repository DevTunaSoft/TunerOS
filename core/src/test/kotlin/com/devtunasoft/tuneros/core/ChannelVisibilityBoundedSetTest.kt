package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Bounded-set coverage for [ChannelVisibility]. The guide is bounded by the parser's 25,000-channel cap and this
 * filter adds no display cap of its own. Both APIs are pure: no result is cached and no new derived text (which could
 * carry a provider URL or token) is emitted. All fixtures below are scratch data.
 */
class ChannelVisibilityBoundedSetTest {
    private fun scratchChannel(index: Int, name: String = "Scratch News $index") = Channel(
        "id-$index", name, "https://example.invalid/scratch/$index.ts",
    )

    @Test fun largeDuplicateIdListKeepsEveryMatchInPlaylistOrder() {
        val channels = List(4_000) { index -> scratchChannel(index % 500, "Scratch News $index") }
        val visible = ChannelVisibility.filter(channels, null, "SCRATCH NEWS", false)
        assertEquals(4_000, visible.size)
        for (index in channels.indices) assertSame(channels[index], visible[index])
    }

    @Test fun emptyAndWhitespaceOnlyInputsStayFailClosed() {
        val empty = emptyList<Channel>()
        assertSame(empty, ChannelVisibility.filter(empty, null, "  \t ", false))
        assertTrue(ChannelVisibility.filter(empty, null, "", true).isEmpty())
        val emptyGuide = GuideSnapshot(emptyList(), emptyMap())
        assertTrue(
            ChannelVisibility.filter(List(3) { scratchChannel(it) }, emptyGuide, " \t\n", true).isEmpty(),
        )
    }

    @Test fun malformedAndMissingGuideIdsAreHiddenWhenFutureOnlyIsOn() {
        val channels = listOf(
            Channel("valid", "Scratch News", "https://example.invalid/valid.ts", guideId = "scratch-good"),
            Channel("blank", "Scratch News", "https://example.invalid/blank.ts", guideId = ""),
            Channel("spaces", "Scratch News", "https://example.invalid/spaces.ts", guideId = "   "),
            Channel("absent", "Scratch News", "https://example.invalid/absent.ts", guideId = "not-in-guide"),
            Channel("no-id", "Scratch News", "https://example.invalid/no-id.ts", guideId = null),
        )
        val guide = GuideSnapshot(
            listOf(GuideProgram("scratch-good", "Future", 20_000, 21_000)), emptyMap(),
        )
        guide.prepareIndex()
        assertEquals(listOf("valid"), ChannelVisibility.filter(channels, guide, "", true).map { it.id })
        assertEquals(listOf("valid"), ChannelVisibility.filter(channels, guide, "NEWS", true).map { it.id })
        assertEquals(5, ChannelVisibility.filter(channels, guide, "NEWS", false).size)
    }

    @Test fun filteringDoesNotMutateInputOrRetainResultsBetweenCalls() {
        val channels = List(6) { scratchChannel(it, if (it % 2 == 0) "Keep" else "Skip") }
        val snapshot = ArrayList(channels)
        val first = ChannelVisibility.filter(channels, null, "keep", false)
        assertEquals(listOf("id-0", "id-2", "id-4"), first.map { it.id })
        assertEquals(snapshot, channels)
        ChannelVisibility.filter(channels, null, "skip", false)
        ChannelVisibility.filter(channels, null, "", false)
        val again = ChannelVisibility.filter(channels, null, "keep", false)
        assertEquals(first, again)
        assertTrue(again !== first, "filtered lists must not be cached and reused")
    }

    @Test fun searchReturnsCallerChannelsWithoutDerivingDiagnosticText() {
        val secretUrl = "https://example.invalid/live?token=SCRATCH_TOKEN_VALUE"
        val channel = Channel("scratch", "Scratch News", secretUrl, userAgent = "scratch-agent")
        val visible = ChannelVisibility.filter(listOf(channel), null, "news", false)
        assertEquals(1, visible.size)
        assertSame(channel, visible.single())
        assertTrue(ChannelVisibility.filter(listOf(channel), null, "SCRATCH_TOKEN_VALUE", false).isEmpty())
        assertTrue(ChannelVisibility.filter(listOf(channel), null, "news", true).isEmpty())
    }
}

package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelVisibilityTest {
    @Test fun allImportedChannelsStayAvailableWithoutFilterOrExtraList() {
        val channels = List(2_361) { i ->
            Channel("id$i", "Channel $i", "https://example.org/$i")
        }
        val visible = ChannelVisibility.filter(channels, null, "", false)
        assertSame(channels, visible)
        assertEquals(2_361, visible.size)
        assertEquals("Channel 2360", visible.last().name)
    }

    @Test fun searchAndFutureOnlyEpgFilterRemainUserControlled() {
        val channels = listOf(
            Channel("a", "News Today", "https://example.org/a", guideId = "future"),
            Channel("b", "News Extra", "https://example.org/b", guideId = null),
            Channel("c", "Weather", "https://example.org/c", guideId = "other"),
        )
        val future = GuideSnapshot(
            listOf(GuideProgram("future", "Tomorrow", 20_000, 21_000)),
            emptyMap(),
        )
        future.prepareIndex()
        assertEquals(listOf("a", "b"),
            ChannelVisibility.filter(channels, future, "NEWS", false).map { it.id })
        assertEquals(listOf("a"),
            ChannelVisibility.filter(channels, future, "news", true).map { it.id })
        assertEquals(listOf("a"),
            ChannelVisibility.filter(channels, future, "", true).map { it.id })
        assertTrue(ChannelVisibility.filter(channels, null, "", true).isEmpty())
        assertTrue(ChannelVisibility.filter(channels, future, "missing", false).isEmpty())
    }
}

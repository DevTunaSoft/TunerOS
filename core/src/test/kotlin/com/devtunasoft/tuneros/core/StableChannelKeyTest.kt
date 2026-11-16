package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StableChannelKeyTest {
    private fun parse(vararg entries: Pair<String, String>): List<Channel> {
        val text = buildString {
            append("#EXTM3U\n")
            for ((extinf, url) in entries) append(extinf).append('\n').append(url).append('\n')
        }
        return PlaylistParser.parseReport(text.reader()).channels
    }

    @Test fun rotatingATokenInTheStreamUrlKeepsTheStableKey() {
        val before = parse("#EXTINF:-1 tvg-id=\"bbc1\",BBC One" to "https://example.org/live/bbc1.m3u8?token=old")
        val after = parse("#EXTINF:-1 tvg-id=\"bbc1\",BBC One" to "https://example.org/live/bbc1.m3u8?token=new")
        assertNotEquals(before.single().id, after.single().id)
        assertEquals(before.single().stableKey, after.single().stableKey)
        assertEquals(before.single().stableKey, before.single().storageKey)
    }

    @Test fun duplicateGuideIdsInOnePlaylistGetDistinctKeysThatRepeatInOrder() {
        val entries = arrayOf(
            "#EXTINF:-1 tvg-id=\"news\",News North" to "https://example.org/n1.m3u8",
            "#EXTINF:-1 tvg-id=\"news\",News South" to "https://example.org/n2.m3u8",
        )
        val channels = parse(*entries)
        assertEquals(2, channels.size)
        assertNotEquals(channels[0].stableKey, channels[1].stableKey)
        assertEquals(channels.map { it.stableKey }, parse(*entries).map { it.stableKey })
    }

    @Test fun withoutAGuideIdTheNameDecidesTheKeyIgnoringCase() {
        val first = parse("#EXTINF:-1,Sports One" to "https://example.org/s1?token=a")
        val second = parse("#EXTINF:-1,sports one" to "https://example.org/s1?token=b")
        assertEquals(first.single().stableKey, second.single().stableKey)
    }

    @Test fun aKeyIsFixedWidthHexAndDoesNotContainTheUrl() {
        val channel = parse("#EXTINF:-1 tvg-id=\"x\",X" to "https://example.org/secret-token-123").single()
        assertEquals(24, channel.stableKey.length)
        assertTrue(channel.stableKey.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(!channel.stableKey.contains("secret"))
    }

    @Test fun storageKeyFallsBackToTheChannelIdWhenNoStableKeyWasSet() {
        val channel = Channel("manual-id", "Manual", "https://example.org/m")
        assertEquals("manual-id", channel.storageKey)
    }
}

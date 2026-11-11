package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaylistMemoryTest {
    @Test fun capPreservesAcceptedChannelsAndDoesNotCountTheirDuplicates() {
        val feed = """#EXTM3U
#EXTINF:-1,First
https://example.org/first
#EXTINF:-1,Second
https://example.org/second
#EXTINF:-1,Overflow
https://example.org/overflow
#EXTINF:-1,First again
https://example.org/first
"""
        val result = PlaylistParser.parseReport(feed.reader(), maxChannels = 2)
        assertEquals(listOf("First", "Second"), result.channels.map { it.name })
        assertTrue(result.warnings.single().contains("1 channels omitted"))
    }

    @Test fun commonProviderHeadersShareOneInstance() {
        val feed = """#EXTM3U
#EXTINF:-1,First
#EXTVLCOPT:http-user-agent=Test TunerOS UA
https://example.org/first
#EXTINF:-1,Second
#EXTVLCOPT:http-user-agent=Test TunerOS UA
https://example.org/second
"""
        val result = PlaylistParser.parse(feed.reader())
        assertEquals(2, result.size)
        assertEquals("Test TunerOS UA", result.first().userAgent)
        assertSame(result.first().userAgent, result.last().userAgent)
    }

    @Test fun largeOverflowDoesNotChangeAcceptedListOrFailImport() {
        val feed = buildString {
            append("#EXTM3U\n")
            repeat(2_000) { n ->
                append("#EXTINF:-1,Channel").append(n).append('\n')
                append("https://example.org/").append(n).append('\n')
            }
        }
        val result = PlaylistParser.parseReport(feed.reader(), maxChannels = 1)
        assertEquals(1, result.channels.size)
        assertEquals("Channel0", result.channels.single().name)
        assertTrue(result.warnings.single().contains("1999 channels omitted"))
    }
}

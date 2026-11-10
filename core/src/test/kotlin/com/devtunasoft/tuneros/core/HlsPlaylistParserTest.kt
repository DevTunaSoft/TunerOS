package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HlsPlaylistParserTest {
    @Test fun parsesMasterVariantsWithoutLoadingMedia() {
        val text = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401f,mp4a.40.2"
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000
high/index.m3u8
"""
        val playlist = HlsPlaylistParserCore.parse(text.reader()) as HlsMasterPlaylist
        assertEquals(2, playlist.variants.size)
        assertEquals(800_000L, playlist.variants[0].bandwidth)
        assertEquals("high/index.m3u8", playlist.variants[1].uri)
    }

    @Test fun mediaSequenceFilteringReturnsOnlyUnseenSegments() {
        val text = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:6.0,
100.ts
#EXTINF:6.0,
101.ts
#EXTINF:6.0,
102.ts
"""
        val playlist = HlsPlaylistParserCore.parse(text.reader(), afterSequenceExclusive = 100) as HlsMediaPlaylist
        assertEquals(100L, playlist.mediaSequence)
        assertEquals(6L, playlist.targetDurationSeconds)
        assertEquals(listOf(101L, 102L), playlist.segments.map { it.sequence })
        assertEquals(listOf("101.ts", "102.ts"), playlist.segments.map { it.uri })
    }

    @Test fun byteRangesDiscontinuityKeyAndMapArePreserved() {
        val text = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:7
#EXT-X-KEY:METHOD=AES-128,URI="key.bin"
#EXT-X-MAP:URI="init.mp4"
#EXT-X-BYTERANGE:100@0
#EXTINF:4,
media.bin
#EXT-X-DISCONTINUITY
#EXT-X-BYTERANGE:50
#EXTINF:4,
media.bin
#EXT-X-ENDLIST
"""
        val playlist = HlsPlaylistParserCore.parse(text.reader()) as HlsMediaPlaylist
        assertTrue(playlist.endList)
        assertEquals(2, playlist.segments.size)
        assertEquals(HlsByteRange(100, 0), playlist.segments[0].byteRange)
        assertEquals(HlsByteRange(50, 100), playlist.segments[1].byteRange)
        assertEquals(false, playlist.segments[0].discontinuity)
        assertEquals(true, playlist.segments[1].discontinuity)
        assertEquals("AES-128", playlist.segments[1].keyMethod)
        assertEquals("key.bin", playlist.segments[1].keyUri)
        assertEquals("init.mp4", playlist.segments[1].initializationMapUri)
    }

    @Test fun implicitByteRangeRequiresSamePreviousResource() {
        val text = """#EXTM3U
#EXT-X-BYTERANGE:100@0
#EXTINF:4,
one.ts
#EXT-X-BYTERANGE:50
#EXTINF:4,
two.ts
"""
        assertFailsWith<IllegalArgumentException> {
            HlsPlaylistParserCore.parse(text.reader())
        }
    }

    @Test fun oversizedHlsLineIsRejectedBeforeUnboundedAllocation() {
        val text = "#EXTM3U\n" + "X".repeat(HlsPlaylistParserCore.MAX_LINE_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> {
            HlsPlaylistParserCore.parse(text.reader())
        }
    }
}

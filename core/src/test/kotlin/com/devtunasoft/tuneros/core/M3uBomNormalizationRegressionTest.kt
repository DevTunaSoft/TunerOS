package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parser-adversarial coverage for UTF-8 BOM normalization in the line-oriented M3U parsers.
 *
 * Both parsers strip a leading BOM so a BOM-prefixed feed is accepted. Leading whitespace is also
 * tolerated by the same line normalization. This fixture asserts the two tolerances compose: a BOM
 * followed by whitespace on the header/first attribute line must not change what the parser accepts.
 * Synthetic fixtures only; no provider data.
 */
class M3uBomNormalizationRegressionTest {
    private val scratchUrl = "https://example.invalid/scratch/seg0.ts"

    @Test fun hlsBomFollowedBySpaceStillYieldsTheHeaderAndSegments() {
        val text = "\uFEFF #EXTM3U\n#EXTINF:2.0,first\nseg0.ts\n"
        val playlist = HlsPlaylistParserCore.parse(text.reader()) as HlsMediaPlaylist
        assertEquals(1, playlist.segments.size, "BOM + space must not suppress the header or the segment")
        assertEquals("seg0.ts", playlist.segments[0].uri)
    }

    @Test fun m3uBomFollowedBySpaceStillImportsTheChannel() {
        val text = "\uFEFF #EXTINF:-1,Scratch News\n$scratchUrl\n"
        val imported = PlaylistParser.parseReport(text.reader())
        assertEquals(1, imported.channels.size, "BOM + space must not suppress the EXTINF attribute line")
        assertEquals("Scratch News", imported.channels[0].name)
    }

    @Test fun bareBomWithoutFollowingSpaceRemainsAccepted() {
        val hls = HlsPlaylistParserCore.parse("\uFEFF#EXTM3U\n#EXTINF:2.0,first\nseg0.ts\n".reader())
        assertEquals(1, (hls as HlsMediaPlaylist).segments.size)
        val imported = PlaylistParser.parseReport("\uFEFF#EXTINF:-1,Scratch News\n$scratchUrl\n".reader())
        assertEquals(1, imported.channels.size)
    }

    @Test fun whitespaceBeforeBomRemainsAccepted() {
        val hls = HlsPlaylistParserCore.parse(" \uFEFF#EXTM3U\n#EXTINF:2.0,first\nseg0.ts\n".reader())
        assertEquals(1, (hls as HlsMediaPlaylist).segments.size)
        val imported = PlaylistParser.parseReport(" \uFEFF#EXTINF:-1,Scratch News\n$scratchUrl\n".reader())
        assertEquals(1, imported.channels.size)
    }
}

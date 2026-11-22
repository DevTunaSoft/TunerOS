package com.devtunasoft.tuneros.core

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression for a late or repeated EXT-X-MEDIA-SEQUENCE tag. The parser used to renumber every segment after the tag,
 * so a playlist could carry the same sequence number twice. The fuzz test found it. RFC 8216 requires the tag once,
 * before the first segment, so such a playlist is now refused.
 */
class HlsMediaSequenceRegressionTest {
    @Test fun aSecondMediaSequenceTagAfterASegmentIsRefused() {
        val text = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:5
#EXTINF:4.0,
a.ts
#EXT-X-MEDIA-SEQUENCE:5
#EXTINF:4.0,
b.ts
"""
        assertFailsWith<IllegalArgumentException> { HlsPlaylistParserCore.parse(StringReader(text)) }
    }

    @Test fun aLateMediaSequenceTagAfterABareUriIsRefused() {
        val text = "#EXTM3U\nhttps://h.example/x.ts\n#EXT-X-MEDIA-SEQUENCE:5\n"
        assertFailsWith<IllegalArgumentException> { HlsPlaylistParserCore.parse(StringReader(text)) }
    }

    @Test fun aTagBeforeTheFirstSegmentStillNumbersEverySegmentInOrder() {
        val text = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:4.0,\na.ts\n#EXTINF:4.0,\nb.ts\n"
        val playlist = HlsPlaylistParserCore.parse(StringReader(text)) as HlsMediaPlaylist
        assertEquals(listOf(5L, 6L), playlist.segments.map { it.sequence })
    }
}

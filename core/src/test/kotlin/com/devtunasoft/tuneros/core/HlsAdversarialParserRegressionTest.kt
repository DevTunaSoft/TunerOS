package com.devtunasoft.tuneros.core

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage for the HLS playlist parser seam. The existing parser, fuzz, media-sequence and
 * range tests cover ordinary bounds and malformed tag soup; these cases pin the arithmetic edges, the result cap,
 * repeated parse cycles and secret-free diagnostics. All URLs and markers below are synthetic and non-routable.
 */
class HlsAdversarialParserRegressionTest {
    private fun parse(text: String, after: Long? = null): HlsPlaylistSnapshot =
        HlsPlaylistParserCore.parse(StringReader(text), after)

    private fun barePlaylist(firstSequence: Long, count: Int): String = buildString {
        append("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:").append(firstSequence).append('\n')
        repeat(count) { append("s-").append(it).append(".ts\n") }
    }

    private fun retainedStateFields(): List<String> =
        HlsPlaylistParserCore::class.java.declaredFields.filter { field ->
            field.type.isArray ||
                MutableCollection::class.java.isAssignableFrom(field.type) ||
                MutableMap::class.java.isAssignableFrom(field.type)
        }.map { it.name }

    @Test fun mediaSequencePastLongMaxValueIsRefusedInsteadOfWrapping() {
        val text = barePlaylist(Long.MAX_VALUE - 1, 3)
        assertFailsWith<IllegalArgumentException>("sequence must not wrap past Long.MAX_VALUE") {
            parse(text)
        }
    }

    @Test fun lastSegmentMayCarryLongMaxValueButNoFurtherSegmentIsAccepted() {
        val atMax = parse(barePlaylist(Long.MAX_VALUE, 1)) as HlsMediaPlaylist
        assertEquals(Long.MAX_VALUE, atMax.segments.single().sequence)
        val endingAtMax = parse(barePlaylist(Long.MAX_VALUE - 1, 2)) as HlsMediaPlaylist
        assertEquals(listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE), endingAtMax.segments.map { it.sequence })
        assertFailsWith<IllegalArgumentException>("a third sequence cannot be represented") {
            parse(barePlaylist(Long.MAX_VALUE - 1, 3))
        }
    }

    @Test fun byteRangeEndPastLongMaxValueIsRefusedAsInvalidPlaylist() {
        val text = "#EXTM3U\n#EXTINF:4,\n#EXT-X-BYTERANGE:100@${Long.MAX_VALUE}\ns.ts\n"
        assertFailsWith<IllegalArgumentException>("range end overflow must be an invalid playlist") {
            parse(text)
        }
    }

    @Test fun resultCapIsEnforcedAtItsExactBoundary() {
        val cap = HlsPlaylistParserCore.MAX_RESULT_SEGMENTS
        val atCap = parse(barePlaylist(0L, cap)) as HlsMediaPlaylist
        assertEquals(cap, atCap.segments.size)
        assertEquals((cap - 1).toLong(), atCap.latestSequence)
        assertFailsWith<IllegalArgumentException>("one segment over the cap must be refused") {
            parse(barePlaylist(0L, cap + 1))
        }
    }

    @Test fun filteredPollCanSkipMoreSegmentsThanTheResultCap() {
        val total = HlsPlaylistParserCore.MAX_RESULT_SEGMENTS + 500
        val playlist = parse(barePlaylist(0L, total), after = total - 3L) as HlsMediaPlaylist
        assertEquals(2, playlist.segments.size)
        assertEquals((total - 2).toLong(), playlist.segments.first().sequence)
        assertEquals((total - 1).toLong(), playlist.latestSequence)
    }

    @Test fun repeatedParseCyclesKeepBoundedResultsAndNoRetainedState() {
        val text = barePlaylist(1_000L, 2_000)
        repeat(20) { cycle ->
            val playlist = parse(text) as HlsMediaPlaylist
            assertEquals(2_000, playlist.segments.size, "cycle $cycle")
            assertEquals(1_000L, playlist.segments.first().sequence, "cycle $cycle")
            assertEquals(2_999L, playlist.segments.last().sequence, "cycle $cycle")
            assertEquals(2_999L, playlist.latestSequence, "cycle $cycle")
        }
        assertTrue(retainedStateFields().isEmpty(), "parser retains collection state: ${retainedStateFields()}")
    }

    @Test fun truncatedTagsAndVariantWithoutUriAreRefused() {
        val marker = "SYNTHETIC-MARKER-5C1"
        val cases = listOf(
            "#EXTM3U\n",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"open\nvariant.m3u8\n",
            "#EXTM3U\n#EXT-X-BYTERANGE:100@\ns.ts\n",
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:9223372036854775808\ns.ts\n",
            "#EXTM3U\n#EXT-X-KEY:URI=\"https://engine.invalid/$marker/key\",METHOD=AES-128,KEYFORMAT=\"open\ns.ts\n",
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@\ns.ts\n",
        )
        cases.forEachIndexed { index, text ->
            assertFailsWith<IllegalArgumentException>("case $index") { parse(text) }
        }
    }

    @Test fun refusalMessagesDoNotEchoSyntheticMarkersOrUrls() {
        val marker = "SYNTHETIC-MARKER-7B2"
        val cases = listOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:$marker\ns.ts\n",
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:$marker\ns.ts\n",
            "#EXTM3U\n#EXTINF:$marker,\ns.ts\n",
            "#EXTM3U\n#EXT-X-BYTERANGE:$marker@0\ns.ts\n",
            "#EXTM3U\n#EXT-X-BYTERANGE:100@$marker\ns.ts\n",
            "#EXTM3U\n#EXT-X-KEY:URI=\"https://engine.invalid/$marker/key\",METHOD=\"open\ns.ts\n",
        )
        cases.forEachIndexed { index, text ->
            val message = runCatching { parse(text) }.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.isNotEmpty(), "case $index did not fail")
            assertTrue(!message.contains(marker), "case $index echoed a marker: $message")
            assertTrue(!message.contains("://"), "case $index echoed a URL: $message")
        }
    }
}

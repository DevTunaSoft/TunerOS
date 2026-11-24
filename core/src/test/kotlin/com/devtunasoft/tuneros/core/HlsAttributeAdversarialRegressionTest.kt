package com.devtunasoft.tuneros.core

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Deterministic parser-adversarial regressions for the HLS attribute scanner at
 * [HlsPlaylistParserCore.parse]. The 50 prior CORE branches exercise HLS tag order, caps, byte ranges,
 * sequence numbers and key rotation, but no sibling test drives the attribute scanner with duplicate
 * names, quoted commas/equals, unusual spacing or unterminated quotes on KEY/MAP/STREAM-INF lines.
 *
 * These tests pin the scanner's observable, bounded behaviour on that untested sub-seam: the first
 * occurrence of a duplicate name wins, quoted punctuation stays inside a value, whitespace around
 * '=' is tolerated, and a malformed quoted value fails closed without echoing untrusted text.
 *
 * All fixtures are synthetic. The marker is derived from a fixed constant at run time, never written
 * as a literal, and no provider URL, credential or media payload is present.
 */
class HlsAttributeAdversarialRegressionTest {
    /** Derived at run time from a constant seed: no literal marker in source or class constant pool. */
    private val marker = "MK-" + (0x5A17C3L xor 0x0A59L).toString(16).uppercase()

    private fun parseMaster(attributeLine: String): HlsMasterPlaylist =
        HlsPlaylistParserCore.parse(
            StringReader("#EXTM3U\n$attributeLine\nvariant.m3u8\n"),
        ) as HlsMasterPlaylist

    private fun parseMedia(tags: String): HlsMediaPlaylist =
        HlsPlaylistParserCore.parse(
            StringReader("#EXTM3U\n$tags\nsegment.ts\n"),
        ) as HlsMediaPlaylist

    @Test
    fun duplicateAttributeNamesResolveToTheFirstOccurrence() {
        val variant = parseMaster(
            "#EXT-X-STREAM-INF:BANDWIDTH=100,BANDWIDTH=200,AUDIO=first,AUDIO=second",
        ).variants.single()
        assertEquals(100L, variant.bandwidth)
        assertEquals("first", variant.audioGroup)
    }

    @Test
    fun quotedCommasAndEqualsStayInsideTheSelectedValue() {
        val variant = parseMaster(
            "#EXT-X-STREAM-INF:BANDWIDTH=300,CODECS=\"avc1,mp4a=2\",AUDIO=\"g,1=x\"",
        ).variants.single()
        assertEquals(300L, variant.bandwidth)
        assertEquals("g,1=x", variant.audioGroup)
    }

    @Test
    fun caseInsensitiveAttributeNamesReachEveryConsumedKeyField() {
        val segment = parseMedia(
            "#EXT-X-KEY:method=AES-128,uri=\"key.bin\",keyformat=\"identity\"," +
                "iv=\"0x00000000000000000000000000000001\"",
        ).segments.single()
        assertEquals("AES-128", segment.keyMethod)
        assertEquals("key.bin", segment.keyUri)
        assertEquals("identity", segment.keyFormat)
        assertEquals("0x00000000000000000000000000000001", segment.keyIv)
    }

    @Test
    fun whitespaceAroundEqualsIsToleratedForUnquotedValuesOnly() {
        // RFC 8216 allows no whitespace around '='. The scanner trims an unquoted value, so
        // "BANDWIDTH = 600" yields 600; a quote must follow '=' immediately to be unquoted,
        // otherwise the surrounding quotes remain part of the value (malformed input stays literal).
        val variant = parseMaster(
            "#EXT-X-STREAM-INF:BANDWIDTH = 600 , AUDIO =\"group\"",
        ).variants.single()
        assertEquals(600L, variant.bandwidth)
        assertEquals("group", variant.audioGroup)
    }

    @Test
    fun anEmptyValuelessAttributeDoesNotShiftLaterValues() {
        val variant = parseMaster(
            "#EXT-X-STREAM-INF:BANDWIDTH=,FOO,BANDWIDTH=900,AUDIO=\"a\"",
        ).variants.single()
        assertNull(variant.bandwidth)
        assertEquals("a", variant.audioGroup)
    }

    @Test
    fun anUnknownAttributeWithAnEqualsSignDoesNotMaskAKnownOne() {
        val variant = parseMaster(
            "#EXT-X-STREAM-INF:X-UNKNOWN=\"k=v\",BANDWIDTH=500",
        ).variants.single()
        assertEquals(500L, variant.bandwidth)
    }

    @Test
    fun aQuotedMapByteRangeWithPunctuationIsParsedFromTheUnquotedValue() {
        val segment = parseMedia(
            "#EXT-X-MAP:URI=\"init.bin,x=y\",BYTERANGE=\"100@5\"",
        ).segments.single()
        assertEquals("init.bin,x=y", segment.initializationMapUri)
        assertEquals(HlsByteRange(length = 100, offset = 5), segment.initializationMapByteRange)
    }

    @Test
    fun anUnterminatedQuotedAttributeFailsClosedWithoutEchoingUntrustedText() {
        val failure = assertFailsWith<IllegalArgumentException> {
            parseMaster("#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"$marker")
        }
        assertFalse(failure.message.orEmpty().contains(marker))
    }
}

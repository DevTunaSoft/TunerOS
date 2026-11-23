package com.devtunasoft.tuneros.core

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * CORE-34: RFC 8216 pins EXT-X-MEDIA-SEQUENCE to a decimal-integer, whose digits are 0..9 only.
 * A sign prefix is not a decimal-integer: a negative start would number segments below zero and
 * feed a negative implicit AES-128 IV to the capture engine.
 *
 * Audit boundary: HlsMediaSequenceRegressionTest covers a repeated or late tag, HlsPlaylistFuzzTest
 * only permits a negative fixture without pinning a decision, and CORE-14 covers overflow plus
 * generic malformed-numeric diagnostics. None of them asserts the sign rule. All URIs below are
 * synthetic relative names; no address, credential or PIN appears in this file.
 */
class HlsMediaSequenceDecimalIntegerTest {
    private fun playlist(sequenceToken: String): String = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-MEDIA-SEQUENCE:").append(sequenceToken).append('\n')
        append("#EXTINF:4.0,\ns0.ts\n")
        append("#EXTINF:4.0,\ns1.ts\n")
    }

    private fun refusedReason(sequenceToken: String): String {
        val error = assertFailsWith<IllegalArgumentException> { HlsPlaylistParserCore.parse(StringReader(playlist(sequenceToken))) }
        val reason = error.message.orEmpty()
        assertEquals("HLS media sequence must be an unsigned decimal-integer", reason)
        assertFalse(reason.contains(sequenceToken), "the refusal must name the rule, not the signed token")
        return reason
    }

    @Test fun aNegativeMediaSequenceIsRefusedBeforeAnySegmentIsNumbered() {
        assertEquals(
            "HLS media sequence must be an unsigned decimal-integer",
            refusedReason("-1"),
        )
    }

    @Test fun theMostNegativeLongMediaSequenceIsRefusedWithoutWrapping() {
        assertEquals(
            "HLS media sequence must be an unsigned decimal-integer",
            refusedReason(Long.MIN_VALUE.toString()),
        )
    }

    @Test fun anExplicitPlusSignIsRefusedLikeANegativeSign() {
        assertEquals(
            "HLS media sequence must be an unsigned decimal-integer",
            refusedReason("+7"),
        )
    }

    @Test fun theSameHostileTokenIsRefusedDeterministicallyAcrossRepeatedParses() {
        val first = refusedReason("-2")
        repeat(64) { assertEquals(first, refusedReason("-2")) }
    }

    @Test fun unsignedSequencesStillNumberSegmentsInOrder() {
        for (token in listOf("0", "7", "9223372036854775806")) {
            val start = token.toLong()
            val parsed = HlsPlaylistParserCore.parse(StringReader(playlist(token))) as HlsMediaPlaylist
            assertEquals(start, parsed.mediaSequence, "token $token")
            assertEquals(listOf(start, start + 1), parsed.segments.map { it.sequence }, "token $token")
        }
    }
}

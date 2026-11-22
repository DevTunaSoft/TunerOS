package com.devtunasoft.tuneros.core

import java.nio.ByteBuffer
import java.util.HexFormat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic fuzzing of the HLS AES-128 initialization vector parser. An explicit IV is accepted exactly when it is
 * "0x" or "0X" followed by 32 hexadecimal digits, and then it decodes to those bytes. An absent IV is the media sequence
 * number in the last eight bytes, big-endian. The oracle uses the JDK's HexFormat and ByteBuffer, not the parser's own
 * code. Every iteration is seeded, so a failure reproduces from the iteration number.
 */
class HlsIvFuzzTest {
    private val wellFormed = Regex("0[xX][0-9a-fA-F]{32}")

    /** Damages one well-formed string in a way that usually breaks it. */
    private fun mutate(text: String, random: Random): String =
        when (random.nextInt(5)) {
            0 -> text.dropLast(1)
            1 -> text + "0"
            2 -> if (text.isEmpty()) "g" else text.replaceRange(text.length - 1, text.length, "g")
            3 -> text.replaceFirst("0", "")
            else -> text.replaceFirst("x", "y").replaceFirst("X", "Y")
        }

    @Test fun explicitIvsAreAcceptedExactlyWhenTheyAreWellFormedHexAndDecodeToThoseBytes() {
        val random = Random(20261122)
        var accepted = 0
        repeat(3_000) { iteration ->
            val bytes = random.nextBytes(16)
            val prefix = listOf("0x", "0X", "0", "")[random.nextInt(4)]
            var text = prefix + HexFormat.of().formatHex(bytes)
            if (random.nextInt(3) == 0) text = mutate(text, random)
            val expected = if (wellFormed.matches(text)) HexFormat.of().parseHex(text.substring(2)) else null
            val actual = HlsAes128.initializationVector(text, mediaSequence = 0L)
            assertEquals(expected == null, actual == null, "iteration $iteration for $text")
            if (expected != null) {
                assertTrue(expected.contentEquals(actual), "iteration $iteration decodes $text differently")
                accepted++
            }
        }
        // Enough well-formed IVs must be accepted to check the decoding path, not only the refusals.
        assertTrue(accepted >= 500, "only $accepted of 3000 explicit IVs were accepted")
    }

    @Test fun anAbsentIvIsTheMediaSequenceNumberInTheLastEightBytesBigEndian() {
        val random = Random(7)
        repeat(1_000) { iteration ->
            val sequence = random.nextLong()
            val expected = ByteBuffer.allocate(16).putLong(8, sequence).array()
            assertTrue(
                expected.contentEquals(HlsAes128.initializationVector(null, sequence)),
                "iteration $iteration for sequence $sequence",
            )
        }
    }
}

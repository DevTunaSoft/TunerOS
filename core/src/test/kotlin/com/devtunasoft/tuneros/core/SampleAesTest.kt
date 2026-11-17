package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SampleAesTest {
    private val key = SampleAesFixtures.key
    private val iv = SampleAesFixtures.iv

    /** The H.264 elementary stream of [segment]: the PES payload after its 9-byte header. */
    private fun videoEs(segment: ByteArray): ByteArray {
        val pes = SampleAesFixtures.payloadOf(segment, SampleAesFixtures.VIDEO_PID)
        return pes.copyOfRange(9, pes.size)
    }

    private fun audioEs(segment: ByteArray): ByteArray {
        val pes = SampleAesFixtures.payloadOf(segment, SampleAesFixtures.AUDIO_PID)
        return pes.copyOfRange(9, pes.size)
    }

    /** Pseudo-random bytes from 4 to 255, so nothing in them needs emulation prevention. */
    private fun body(size: Int, seed: Long): ByteArray {
        val random = Random(seed)
        return ByteArray(size) { (4 + random.nextInt(252)).toByte() }
    }

    /** The NAL unit as the rule should leave it when its RBSP is at most 64 bytes: only the block at 32 is encrypted. */
    private fun expectedWithOneEncryptedBlock(nal: ByteArray): ByteArray {
        val expected = nal.copyOf()
        SampleAesFixtures.cbc(key, iv, nal.copyOfRange(32, 48)).copyInto(expected, 32)
        return expected
    }

    @Test fun videoPatternKeepsTheClearLeadAndEncryptsOneBlockInTen() {
        val nal = SampleAesFixtures.nal(0x65, body(300, 1))
        val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(nal))
        val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
        val actual = videoEs(encrypted).copyOfRange(4, 4 + nal.size)
        // The RBSP is 301 bytes: blocks at 32 and 192 are encrypted, the others (and the first 32 bytes) stay clear.
        val blocks = SampleAesFixtures.cbc(key, iv, nal.copyOfRange(32, 48) + nal.copyOfRange(192, 208))
        val expected = nal.copyOf()
        blocks.copyInto(expected, 32, 0, 16)
        blocks.copyInto(expected, 192, 16, 32)
        assertTrue(expected.contentEquals(actual))
    }

    @Test fun theBoundaryAfterTheLeadEncryptsOnlyAFullBlockThatHasABytePastIt() {
        // RBSP 49: one byte past the lead's block. RBSP 64: a final full block at 48 stays clear.
        for (size in listOf(49, 64)) {
            val nal = SampleAesFixtures.nal(0x41, body(size - 1, size.toLong()))
            val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(nal))
            val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
            assertTrue(expectedWithOneEncryptedBlock(nal).contentEquals(videoEs(encrypted).copyOfRange(4, 4 + nal.size)))
        }
    }

    @Test fun shortSlicesAndNonSliceNalUnitsAreNotEncrypted() {
        val short = SampleAesFixtures.nal(0x41, body(47, 2)) // RBSP of 48 bytes is at the limit and stays clear
        val sei = SampleAesFixtures.nal(0x06, body(400, 3)) // SEI is never encrypted
        val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(short, sei))
        val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
        assertTrue(clear.contentEquals(encrypted))
    }

    @Test fun aSegmentWithEmulationPreventionAndAudioDecryptsBackToItself() {
        val random = Random(9)
        val nals = (0 until 6).map { index ->
            val rbsp = ByteArray(120 + index * 61) {
                if (random.nextInt(3) == 0) 0.toByte() else random.nextInt(256).toByte()
            }
            rbsp[rbsp.size - 1] = 0x80.toByte()
            SampleAesFixtures.escape(SampleAesFixtures.nal(if (index % 2 == 0) 0x65 else 0x41, rbsp))
        }
        val audio = listOf(body(100, 4), body(16, 5), body(33, 6))
            .map { SampleAesFixtures.adts(it) }
            .reduce { a, b -> a + b }
        val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(*nals.toTypedArray()), audio)
        val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
        assertFalse(clear.contentEquals(encrypted))
        assertTrue(clear.contentEquals(SampleAes.decryptSegment(encrypted, key, iv)!!))
        // Re-inserted emulation prevention keeps each NAL unit free of start-code lookalikes.
        assertEquals(nals.size, SampleAesFixtures.startCodeCount(videoEs(encrypted)))
    }

    @Test fun cabacZeroWordsAtTheEndOfASliceRoundTrip() {
        val rbsp = SampleAesFixtures.nal(0x65, body(200, 7)) + byteArrayOf(0x80.toByte(), 0, 0, 0, 0)
        val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(SampleAesFixtures.escape(rbsp)))
        val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
        assertFalse(clear.contentEquals(encrypted))
        assertTrue(clear.contentEquals(SampleAes.decryptSegment(encrypted, key, iv)!!))
    }

    @Test fun anOddNumberOfTrailingZeroBytesIsRefused() {
        // Three zero bytes after the stop bit cannot come from an encoder: cabac_zero_words come in pairs.
        val raw = SampleAesFixtures.nal(0x65, body(200, 8)) + byteArrayOf(0x80.toByte(), 0, 0, 0, 3)
        val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(raw))
        assertNull(SampleAes.encryptSegment(clear, key, iv))
    }

    @Test fun audioKeepsSixteenClearBytesAndRestartsTheChainInEveryFrame() {
        val payloads = listOf(body(100, 10), body(16, 11), body(33, 12))
        val audio = payloads.map { SampleAesFixtures.adts(it) }.reduce { a, b -> a + b }
        // An access unit delimiter is not media that is encrypted, so it is left as it is.
        val video = SampleAesFixtures.annexB(SampleAesFixtures.nal(0x09, byteArrayOf(0x10)))
        val clear = SampleAesFixtures.segment(video, audio)
        val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
        val expected = ByteArrayOutputStream()
        for (payload in payloads) {
            val body = payload.copyOf()
            val encryptedLength = if (payload.size > 16) ((payload.size - 16) / 16) * 16 else 0
            if (encryptedLength > 0) {
                SampleAesFixtures.cbc(key, iv, payload.copyOfRange(16, 16 + encryptedLength)).copyInto(body, 16)
            }
            expected.write(SampleAesFixtures.adts(payload).copyOfRange(0, 7))
            expected.write(body)
        }
        assertTrue(expected.toByteArray().contentEquals(audioEs(encrypted)))
    }

    @Test fun aWrongKeyStillReturnsBytesBecauseSampleAesCarriesNoIntegrityCheck() {
        val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(SampleAesFixtures.nal(0x65, body(300, 13))))
        val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
        val wrong = SampleAes.decryptSegment(encrypted, ByteArray(16) { 0x55 }, iv)
        assertTrue(wrong != null)
        assertFalse(clear.contentEquals(wrong!!))
    }

    @Test fun aPesThatShrinksIsRepackedIntoItsStuffingAndDecryptsToItsCanonicalForm() {
        val prefix = body(10, 15)
        val suffix = body(290, 16)
        // The first NAL unit carries an emulation-prevention byte that is not needed (03 before 05). Reading it drops that byte.
        val withExtraEscape = byteArrayOf(0x65.toByte()) + prefix + byteArrayOf(0, 0, 3, 5) + suffix
        val canonical = byteArrayOf(0x65.toByte()) + prefix + byteArrayOf(0, 0, 5) + suffix
        val clearWith = SampleAesFixtures.segment(SampleAesFixtures.annexB(withExtraEscape))
        val clearCanonical = SampleAesFixtures.segment(SampleAesFixtures.annexB(canonical))
        val encrypted = SampleAes.encryptSegment(clearWith, key, iv)!!
        assertEquals(clearWith.size, encrypted.size)
        assertEquals(
            SampleAesFixtures.payloadOf(clearWith, SampleAesFixtures.VIDEO_PID).size - 1,
            SampleAesFixtures.payloadOf(encrypted, SampleAesFixtures.VIDEO_PID).size,
        )
        assertTrue(clearCanonical.contentEquals(SampleAes.decryptSegment(encrypted, key, iv)!!))
    }

    @Test fun thePcrOfTheVideoPacketSurvivesTheRewrite() {
        val clear = SampleAesFixtures.segment(SampleAesFixtures.annexB(SampleAesFixtures.nal(0x65, body(300, 17))))
        val encrypted = SampleAes.encryptSegment(clear, key, iv)!!
        val first = SampleAesFixtures.firstPacketOf(clear, SampleAesFixtures.VIDEO_PID)
        // The packet header, the adaptation length, the flags and the six PCR bytes.
        assertTrue(clear.copyOfRange(first, first + 12).contentEquals(encrypted.copyOfRange(first, first + 12)))
    }

    @Test fun segmentsThisCodeCannotRewriteAreRefused() {
        val video = SampleAesFixtures.annexB(SampleAesFixtures.nal(0x65, body(300, 18)))
        assertNull(SampleAes.encryptSegment(SampleAesFixtures.segment(video, extraType = 0x81), key, iv)) // AC-3
        assertNull(SampleAes.encryptSegment(SampleAesFixtures.segment(video, extraType = 0x24), key, iv)) // HEVC
        assertNull(SampleAes.encryptSegment(SampleAesFixtures.tablesOnly(), key, iv)) // no media
        val full = SampleAesFixtures.segment(video)
        assertNull(SampleAes.encryptSegment(full.copyOfRange(2 * 188, full.size), key, iv)) // no program table
        assertNull(SampleAes.encryptSegment(full.copyOf(full.size - 1), key, iv)) // not whole packets
        assertNull(SampleAes.encryptSegment(ByteArray(188) { 1.toByte() }, key, iv)) // not transport packets
        assertNull(SampleAes.encryptSegment(full, ByteArray(8), iv)) // key length
        assertNull(SampleAes.decryptSegment(full, key, ByteArray(15))) // IV length
    }
}

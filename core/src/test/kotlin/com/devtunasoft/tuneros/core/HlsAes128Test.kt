package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsAes128Test {
    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private val iv = ByteArray(16) { (it * 11 + 5).toByte() }

    private fun encrypt(plain: ByteArray, key: ByteArray = this.key, iv: ByteArray = this.iv): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").let {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            it.doFinal(plain)
        }

    private fun decryptInPieces(cipherText: ByteArray, piece: Int, key: ByteArray = this.key): ByteArray {
        val sink = ByteArrayOutputStream()
        val decryptor = HlsAes128.Decryptor(key, iv, sink)
        var offset = 0
        while (offset < cipherText.size) {
            val size = minOf(piece, cipherText.size - offset)
            decryptor.write(cipherText, offset, size)
            offset += size
        }
        decryptor.finish()
        return sink.toByteArray()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test fun streamedDecryptionMatchesTheWholeSegmentForAnyPieceSize() {
        val plain = ByteArray(100_003) { (it % 251).toByte() }
        val cipherText = encrypt(plain)
        for (piece in listOf(1, 15, 16, 17, 4_096, 65_536, 200_000)) {
            assertTrue(plain.contentEquals(decryptInPieces(cipherText, piece)), "piece size $piece")
        }
    }

    @Test fun plaintextCountExcludesThePaddingThatReachedNoSink() {
        val plain = ByteArray(33) { it.toByte() }
        val cipherText = encrypt(plain)
        val sink = ByteArrayOutputStream()
        val decryptor = HlsAes128.Decryptor(key, iv, sink)
        decryptor.write(cipherText, 0, cipherText.size)
        decryptor.finish()
        assertEquals(33L, decryptor.plaintextBytes)
        assertEquals(33, sink.size())
    }

    @Test fun truncatedCiphertextFailsClosedAtFinish() {
        val cipherText = encrypt(ByteArray(40) { 1 })
        val decryptor = HlsAes128.Decryptor(key, iv, ByteArrayOutputStream())
        decryptor.write(cipherText, 0, cipherText.size - 5)
        assertFailsWith<IOException> { decryptor.finish() }
    }

    @Test fun wrongKeyIsDetectedAsInvalidPadding() {
        val cipherText = encrypt(ByteArray(64) { 7 })
        val wrongKey = ByteArray(16) { 0x55 }
        val decryptor = HlsAes128.Decryptor(wrongKey, iv, ByteArrayOutputStream())
        decryptor.write(cipherText, 0, cipherText.size)
        assertFailsWith<IOException> { decryptor.finish() }
    }

    @Test fun closingTheDecryptorLeavesTheSinkOpen() {
        var closed = false
        val sink = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun close() {
                closed = true
            }
        }
        HlsAes128.Decryptor(key, iv, sink).close()
        assertFalse(closed)
    }

    @Test fun ivDefaultsToTheMediaSequenceNumberAndAcceptsExplicitHex() {
        assertEquals("0".repeat(28) + "0102", hex(HlsAes128.initializationVector(null, 258L)!!))
        assertEquals("0102030405060708", hex(HlsAes128.initializationVector(null, 0x0102030405060708L)!!).takeLast(16))
        assertEquals(
            "000102030405060708090a0b0c0d0e0f",
            hex(HlsAes128.initializationVector("0x000102030405060708090A0B0C0D0E0F", 99L)!!),
        )
        assertNull(HlsAes128.initializationVector("0x0102", 1L))
        assertNull(HlsAes128.initializationVector("000102030405060708090A0B0C0D0E0F", 1L))
        assertNull(HlsAes128.initializationVector("0xZZ0102030405060708090A0B0C0D0E0F", 1L))
    }
}

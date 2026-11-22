package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential test of the HLS AES-128 decryptor against the JDK's AES/CBC/PKCS5Padding. Random segments, some valid and
 * some truncated or corrupted, are decrypted in random pieces. Both implementations must agree: the same plaintext, or a
 * refusal from both. Every iteration is seeded, so a failure reproduces from the iteration number.
 */
class HlsAesDifferentialTest {
    private fun jdkDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? =
        try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }.doFinal(data)
        } catch (refused: BadPaddingException) {
            null
        } catch (refused: IllegalBlockSizeException) {
            null
        }

    private fun decryptorDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, random: Random): ByteArray? {
        val out = ByteArrayOutputStream()
        val decryptor = HlsAes128.Decryptor(key, iv, out)
        var offset = 0
        while (offset < data.size) {
            val piece = minOf(random.nextInt(1, 40), data.size - offset)
            decryptor.write(data, offset, piece)
            offset += piece
        }
        return try {
            decryptor.finish()
            out.toByteArray()
        } catch (refused: IOException) {
            null
        }
    }

    @Test fun theDecryptorAgreesWithTheJdkOnValidAndDamagedSegments() {
        val random = Random(20261122)
        var agreedOnPlaintext = 0
        var agreedOnRefusal = 0
        repeat(1_500) { iteration ->
            val key = random.nextBytes(16)
            val iv = random.nextBytes(16)
            val plaintext = random.nextBytes(random.nextInt(0, 200))
            val encrypt = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
            var data = encrypt.doFinal(plaintext)
            if (random.nextInt(3) == 0 && data.isNotEmpty()) {
                data = if (random.nextBoolean()) {
                    data.copyOf(random.nextInt(0, data.size))
                } else {
                    data.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                }
            }
            val expected = jdkDecrypt(key, iv, data)
            val actual = decryptorDecrypt(key, iv, data, random)
            assertEquals(expected == null, actual == null, "iteration $iteration")
            if (expected != null) {
                assertTrue(actual != null && expected.contentEquals(actual), "iteration $iteration")
                agreedOnPlaintext++
            } else {
                agreedOnRefusal++
            }
        }
        // Both outcomes must be common, or the comparison above could pass without checking one of them.
        assertTrue(agreedOnPlaintext >= 100 && agreedOnRefusal >= 100, "plaintext $agreedOnPlaintext, refusal $agreedOnRefusal")
    }
}

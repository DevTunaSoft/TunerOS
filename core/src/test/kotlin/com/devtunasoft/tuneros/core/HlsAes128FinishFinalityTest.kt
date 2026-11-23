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

/**
 * CORE-38: failure finality of the HLS AES-128 stream decryptor.
 *
 * Audit boundary: CORE-04's HlsAes128Test and differential test check one finish call on a valid or
 * truncated segment, and CORE-14 checks engine diagnostics. None calls finish again after a refusal
 * and none exercises flush. The contract says finish "must succeed for a complete segment": a segment
 * whose padding was rejected must not turn into a silent success on a second call, and a flush must not
 * finalize a segment. All values below are synthetic scratch bytes; no address, credential, PIN or
 * media payload appears here.
 */
class HlsAes128FinishFinalityTest {
    private val key = ByteArray(16) { (it * 5 + 1).toByte() }
    private val iv = ByteArray(16) { (it * 3 + 7).toByte() }

    private fun encrypt(plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").let {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            it.doFinal(plain)
        }

    private fun reasonOf(block: () -> Unit): String {
        val error = assertFailsWith<IOException> { block() }
        return error.message.orEmpty()
    }

    @Test fun aRejectedFinishStaysRejectedOnEveryRetry() {
        val cipherText = encrypt(ByteArray(48) { 4 })
        val decryptor = HlsAes128.Decryptor(key, iv, ByteArrayOutputStream())
        decryptor.write(cipherText, 0, cipherText.size - 3)
        val bytesAfterWrite = decryptor.plaintextBytes
        val first = reasonOf { decryptor.finish() }
        assertEquals("AES-128 segment is not whole blocks", first)
        for (attempt in 1..3) {
            assertEquals(first, reasonOf { decryptor.finish() }, "retry $attempt")
        }
        assertEquals(bytesAfterWrite, decryptor.plaintextBytes, "a rejected retry wrote plaintext")
    }

    @Test fun aSuccessfulFinishIsIdempotentAndLeavesTheSinkOpen() {
        val plain = ByteArray(64) { (it % 17).toByte() }
        val cipherText = encrypt(plain)
        val sink = CountingSink()
        val decryptor = HlsAes128.Decryptor(key, iv, sink)
        decryptor.write(cipherText, 0, cipherText.size)
        decryptor.finish()
        val bytes = sink.size
        decryptor.finish()
        assertEquals(plain.size.toLong(), decryptor.plaintextBytes)
        assertEquals(plain.size, bytes)
        assertEquals(bytes, sink.size, "a repeated finish wrote more plaintext")
        assertFalse(sink.closed, "finish closed the caller-owned sink")
    }

    @Test fun flushOnlyForwardsAndDoesNotFinalizeOrCloseTheSink() {
        val plain = ByteArray(64) { 9 }
        val cipherText = encrypt(plain)
        val sink = CountingSink()
        val decryptor = HlsAes128.Decryptor(key, iv, sink)
        decryptor.write(cipherText, 0, cipherText.size)
        val beforeFlush = decryptor.plaintextBytes
        decryptor.flush()
        assertEquals(1, sink.flushes)
        assertEquals(0, sink.closedCount)
        assertEquals(beforeFlush, decryptor.plaintextBytes, "flush released extra plaintext")
        assertEquals(plain.size.toLong(), beforeFlush, "the JDK update already produced every byte")
        decryptor.finish()
        assertEquals(plain.size, sink.size)
        assertEquals(0, sink.closedCount)
    }

    private class CountingSink : OutputStream() {
        var size = 0
        var flushes = 0
        var closedCount = 0
        val closed: Boolean get() = closedCount > 0

        override fun write(b: Int) {
            size++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            size += length
        }

        override fun flush() {
            flushes++
        }

        override fun close() {
            closedCount++
        }
    }
}

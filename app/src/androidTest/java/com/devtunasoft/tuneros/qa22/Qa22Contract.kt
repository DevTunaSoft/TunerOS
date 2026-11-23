package com.devtunasoft.tuneros.qa22

import com.devtunasoft.tuneros.core.HlsAes128
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

/** Android-free QA-22 mirror: same synthetic key/IV rules as the host gate, no fixture file I/O. */
object Qa22Contract {
    const val SEED = 20261123L
    const val MIRROR_BYTES = 256
    const val MIRROR_DIGEST = "4b0f6ccd3c58b310cdad1b13961fc5d88644ebfdef6204bb31ffd45d602523d2"

    private fun key(): ByteArray = ByteArray(16) { index ->
        ((SEED ushr (8 * (index % 4))) xor (index * 37 + 11).toLong()).toByte()
    }

    private fun iv(): ByteArray = ByteArray(16) { index ->
        ((SEED ushr (8 * (index % 4))) xor (index * 53 + 29).toLong()).toByte()
    }

    private fun pattern(length: Int): ByteArray = ByteArray(length) { index ->
        ((index * 31 + (SEED xor (index.toLong() shl 7))).toInt() and 0xFF).toByte()
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (value in bytes) {
            append("0123456789abcdef".get((value.toInt() shr 4) and 0xF))
            append("0123456789abcdef".get(value.toInt() and 0xF))
        }
    }

    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), IvParameterSpec(iv()))
        return cipher.doFinal(plain)
    }

    private class Sink : OutputStream() {
        private val out = ByteArrayOutputStream()
        var closed = false
            private set

        override fun write(value: Int) = out.write(value)

        override fun write(bytes: ByteArray, off: Int, len: Int) = out.write(bytes, off, len)

        override fun close() {
            closed = true
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    fun runChecks(): List<String> {
        val lines = mutableListOf<String>()
        fun check(id: String, ok: Boolean) {
            lines.add(if (ok) "CHECK\tPASS\t$id" else "CHECK\tFAIL\t$id")
        }

        val implicit = HlsAes128.initializationVector(null, 258L)
        val expectedImplicit = ByteBuffer.allocate(16).putLong(8, 258L).array()
        check("MIRROR_IV_DEFAULT", implicit != null && implicit.contentEquals(expectedImplicit))

        val explicitText = "0x" + hex(iv())
        val explicit = HlsAes128.initializationVector(explicitText, 1L)
        check("MIRROR_IV_EXPLICIT", explicit != null && explicit.contentEquals(iv()))
        check("MIRROR_IV_REJECT", HlsAes128.initializationVector(explicitText.dropLast(1), 1L) == null)

        val plain = pattern(MIRROR_BYTES)
        val cipherText = encrypt(plain)
        val sink = Sink()
        val decryptor = HlsAes128.Decryptor(key(), iv(), sink)
        var offset = 0
        while (offset < cipherText.size) {
            val size = minOf(17, cipherText.size - offset)
            decryptor.write(cipherText, offset, size)
            offset += size
        }
        decryptor.finish()
        decryptor.close()
        check("MIRROR_DECRYPT", sink.bytes().contentEquals(plain))
        check("MIRROR_COUNT", decryptor.plaintextBytes == sink.bytes().size.toLong())
        check("MIRROR_SINK_OPEN", !sink.closed)

        val truncatedSink = Sink()
        val truncated = HlsAes128.Decryptor(key(), iv(), truncatedSink)
        truncated.write(cipherText, 0, cipherText.size - 5)
        val refused = try {
            truncated.finish()
            false
        } catch (failure: IOException) {
            true
        }
        check("MIRROR_TRUNCATED_REJECT", refused)

        check("MIRROR_MANIFEST", digest(pattern(MIRROR_BYTES)) == MIRROR_DIGEST)
        return lines
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = runChecks()
        for (line in lines) println(line)
        exitProcess(if (lines.any { it.startsWith("CHECK\tFAIL") }) 1 else 0)
    }
}

package com.devtunasoft.tuneros.core

import java.io.IOException
import java.io.OutputStream
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HLS `METHOD=AES-128` helpers: whole-segment AES-128-CBC with PKCS#7 padding, nothing else. */
object HlsAes128 {
    const val KEY_BYTES = 16
    private const val BLOCK_BYTES = 16
    private const val CHUNK_BYTES = 64 * 1024

    /**
     * The IV the playlist names, or the segment's media sequence number when none is named (RFC 8216).
     * Returns null for a malformed explicit IV, so the caller can fail closed.
     */
    fun initializationVector(explicitHex: String?, mediaSequence: Long): ByteArray? {
        if (explicitHex == null) {
            val iv = ByteArray(BLOCK_BYTES)
            for (index in 0 until 8) {
                iv[BLOCK_BYTES - 1 - index] = (mediaSequence ushr (8 * index)).toByte()
            }
            return iv
        }
        val hex = explicitHex.removePrefix("0x").removePrefix("0X")
        if (explicitHex.length != hex.length + 2 || hex.length != BLOCK_BYTES * 2) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(BLOCK_BYTES) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Streams one segment's ciphertext into plaintext. Ciphertext goes in through write(); plaintext goes to
     * [sink] as it is produced, in bounded chunks. [finish] removes the padding and must succeed for a complete
     * segment. [close] never closes the sink, which carries the whole recording.
     */
    class Decryptor(key: ByteArray, iv: ByteArray, private val sink: OutputStream) : OutputStream() {
        private val cipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").also {
            require(key.size == KEY_BYTES) { "AES-128 key must be 16 bytes" }
            require(iv.size == BLOCK_BYTES) { "AES-128 IV must be 16 bytes" }
            it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        private val scratch = ByteArray(cipher.getOutputSize(CHUNK_BYTES))
        private val single = ByteArray(1)
        private var finished = false
        private var finishFailure: String? = null

        /** Plaintext bytes that have reached the sink so far. */
        var plaintextBytes: Long = 0
            private set

        override fun write(b: Int) {
            single[0] = b.toByte()
            write(single, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished) { "AES-128 segment already finished" }
            var position = off
            var remaining = len
            while (remaining > 0) {
                val piece = minOf(remaining, CHUNK_BYTES)
                val produced = cipher.update(b, position, piece, scratch)
                if (produced > 0) {
                    sink.write(scratch, 0, produced)
                    plaintextBytes += produced
                }
                position += piece
                remaining -= piece
            }
        }

        /** Completes the segment. Throws when the ciphertext is not whole blocks or the padding is invalid. */
        fun finish() {
            // Retrying doFinal on a damaged segment can yield garbage plaintext and a silent
            // success, so a rejection is remembered and repeated on every later call.
            finishFailure?.let { throw IOException(it) }
            if (finished) return
            finished = true
            val last = try {
                cipher.doFinal()
            } catch (error: IllegalBlockSizeException) {
                reject("AES-128 segment is not whole blocks")
            } catch (error: BadPaddingException) {
                reject("AES-128 padding is invalid")
            }
            if (last.isNotEmpty()) {
                sink.write(last)
                plaintextBytes += last.size
            }
        }

        private fun reject(message: String): Nothing {
            finishFailure = message
            throw IOException(message)
        }

        override fun flush() = sink.flush()

        override fun close() = Unit
    }
}

package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CORE-56: the AES-128 key cache is a bounded, rotating working set. These regressions cover
 * key reuse, eviction after more keys than the cache holds, and a METHOD=NONE segment following
 * encrypted ones. All fixtures are synthetic bytes and synthetic addresses; no provider data,
 * credentials or media are used.
 */
class HlsKeyCacheRecoveryRegressionTest {
    private class FakeSource(
        private val playlist: LoadedHlsPlaylist,
        private val bodies: Map<String, ByteArray>,
        private val keys: Map<String, ByteArray>,
    ) : HlsCaptureSource {
        val keyRequests = ArrayList<String>()
        val copied = ArrayList<String>()

        override fun loadKey(url: String): ByteArray {
            keyRequests += url
            return keys[url] ?: error("no synthetic key for this address")
        }

        override fun loadPlaylist(url: String, afterSequenceExclusive: Long?): LoadedHlsPlaylist = playlist

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            val bytes = bodies[url] ?: error("no synthetic segment for this address")
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    private fun keyOf(index: Int): ByteArray = ByteArray(16) { (index * 16 + it + 1).toByte() }

    private fun ivOf(sequence: Long): ByteArray = ByteArray(16).also { iv ->
        for (index in 0 until 8) iv[15 - index] = (sequence ushr (8 * index)).toByte()
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, sequence: Long): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").let {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivOf(sequence)))
            it.doFinal(plain)
        }

    private fun run(segments: List<HlsSegment>, bodies: Map<String, ByteArray>, keys: Map<String, ByteArray>): Pair<ByteArray, FakeSource> {
        val url = "https://fixture.invalid/live.m3u8"
        val source = FakeSource(
            LoadedHlsPlaylist(url, HlsMediaPlaylist(1, 4, true, segments)),
            bodies,
            keys,
        )
        val out = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(url, out, 1_000, source, { false }, { 100 }, {})
        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        return out.toByteArray() to source
    }

    @Test fun aKeyEvictedByRotationIsFetchedAgainAndStillDecryptsItsSegment() {
        // The cache holds four keys. Six distinct keys rotate through it, then the first key
        // returns: it must be fetched again and decrypt with its own bytes, never a neighbour's.
        val addresses = (1..6).map { "https://fixture.invalid/k$it.bin" }
        val keys = (1..6).associate { addresses[it - 1] to keyOf(it) }
        val plaintexts = (1..7).associate { n -> n to ByteArray(24) { i -> (n * 31 + i).toByte() } }
        val keyForSegment = (1..7).associateWith { n -> if (n == 7) 1 else n }
        val bodies = (1..7).associate { index ->
            "https://fixture.invalid/s$index.ts" to
                encrypt(plaintexts[index]!!, keyOf(keyForSegment[index]!!), index.toLong())
        }
        val segments = (1..7).map { index ->
            HlsSegment(index.toLong(), "s$index.ts", 4.0, null, false, "AES-128",
                addresses[keyForSegment[index]!! - 1], null)
        }
        val (written, source) = run(segments, bodies, keys)

        val expected = ByteArray(7 * 24) { index -> plaintexts[index / 24 + 1]!![index % 24] }
        assertTrue(expected.contentEquals(written), "every segment must decrypt with its own key")
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1).map { n -> addresses[n - 1] }, source.keyRequests)
    }

    @Test fun aMethodNoneSegmentAfterEncryptedSegmentsIsWrittenUnchangedAndFetchesNoKey() {
        val keyAddress = "https://fixture.invalid/key.bin"
        val encryptedPlain = ByteArray(32) { (it + 7).toByte() }
        val clearPlain = ByteArray(19) { (it + 3).toByte() }
        val bodies = mapOf(
            "https://fixture.invalid/1.ts" to encrypt(encryptedPlain, keyOf(1), 1L),
            "https://fixture.invalid/2.ts" to clearPlain,
        )
        val segments = listOf(
            HlsSegment(1, "1.ts", 4.0, null, false, "AES-128", keyAddress, null),
            HlsSegment(2, "2.ts", 4.0, null, false, "NONE", null, null),
        )
        val (written, source) = run(segments, bodies, mapOf(keyAddress to keyOf(1)))

        assertTrue((encryptedPlain + clearPlain).contentEquals(written))
        assertEquals(listOf(keyAddress), source.keyRequests)
    }
}

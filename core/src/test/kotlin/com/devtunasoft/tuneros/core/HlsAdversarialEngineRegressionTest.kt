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
 * Deterministic adversarial coverage for the HLS capture engine. Existing engine tests cover one-shot key reuse,
 * gaps, stalls and single failure reasons; these cases pin bounded key rotation across a long recording, arithmetic
 * boundaries at Long.MAX_VALUE and diagnostic redaction. All URLs, keys and markers are synthetic and non-routable.
 */
class HlsAdversarialEngineRegressionTest {
    private class RecordingSource(
        private val playlists: List<LoadedHlsPlaylist>,
        private val segments: Map<String, ByteArray>,
        private val keys: Map<String, ByteArray>,
    ) : HlsCaptureSource {
        private var polls = 0
        val keyRequests = ArrayList<String>()
        val copied = ArrayList<String>()

        override fun loadPlaylist(url: String, afterSequenceExclusive: Long?): LoadedHlsPlaylist =
            playlists[minOf(polls++, playlists.lastIndex)]

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            val bytes = segments[url] ?: error("missing synthetic segment bytes")
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }

        override fun loadKey(url: String): ByteArray {
            keyRequests += url
            return keys[url] ?: error("missing synthetic key bytes")
        }
    }

    private fun key(index: Int): ByteArray = ByteArray(16) { (it + index + 1).toByte() }

    private fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { iv ->
        for (index in 0 until 8) iv[15 - index] = (sequence ushr (8 * index)).toByte()
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, sequence: Long): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").let {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(sequenceIv(sequence)))
            it.doFinal(plain)
        }

    private fun record(
        url: String,
        source: HlsCaptureSource,
        output: ByteArrayOutputStream = ByteArrayOutputStream(),
    ): HlsCaptureEngine.Result = HlsCaptureEngine().record(
        initialUrl = url,
        output = output,
        endEpochSeconds = 1_000,
        source = source,
        cancelled = { false },
        nowEpochSeconds = { 100 },
        sleepMillis = {},
    )

    @Test fun rotatingKeysEvictOldEntriesAndFetchThemAgain() {
        val base = "https://engine14.invalid"
        val url = "$base/live.m3u8"
        val keyUrls = (0..4).associateWith { index -> "$base/k$index.bin" }
        val keyBytes = (0..4).associateWith(::key)
        val keyIndexes = listOf(0, 1, 2, 3, 4, 0)
        val plaintexts = keyIndexes.indices.map { index -> ByteArray(16) { (it + index).toByte() } }
        val segments = keyIndexes.mapIndexed { index, keyIndex ->
            val sequence = 100L + index
            HlsSegment(sequence, "s$index.ts", 4.0, null, false, "AES-128", "k$keyIndex.bin", null)
        }
        val playlist = LoadedHlsPlaylist(url, HlsMediaPlaylist(100, 4, true, segments))
        val source = RecordingSource(
            listOf(playlist),
            segments = segments.mapIndexed { index, segment ->
                "$base/${segment.uri}" to encrypt(plaintexts[index], keyBytes[keyIndexes[index]]!!, 100L + index)
            }.toMap(),
            keys = keyBytes.mapKeys { (index, _) -> keyUrls[index]!! },
        )
        val output = ByteArrayOutputStream()
        val result = record(url, source, output)

        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertTrue(plaintexts.reduce { left, right -> left + right }.contentEquals(output.toByteArray()))
        assertEquals(
            listOf(keyUrls[0], keyUrls[1], keyUrls[2], keyUrls[3], keyUrls[4], keyUrls[0]),
            source.keyRequests,
        )
        assertEquals(16L * keyIndexes.size, result.bytesWritten)
    }

    @Test fun unsupportedMethodDiagnosticDoesNotEchoUntrustedUrlOrCredentialText() {
        val marker = "SYNTHETIC-CREDENTIAL-3A9F"
        val method = "SAMPLE-AES://user:passwd@engine14.invalid/$marker/path"
        val url = "https://engine14.invalid/live.m3u8"
        val source = RecordingSource(
            listOf(LoadedHlsPlaylist(url, HlsMediaPlaylist(1, 4, true, listOf(
                HlsSegment(1, "s.ts", 4.0, null, false, method, "k.bin", null),
            )))),
            emptyMap(),
            emptyMap(),
        )
        val result = record(url, source)

        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        val detail = result.detail.orEmpty()
        assertTrue(detail.contains("not supported"), "unexpected detail: $detail")
        assertTrue(!detail.contains(marker), "detail echoed a marker: $detail")
        assertTrue(!detail.contains("://"), "detail echoed a URL: $detail")
        assertTrue(!detail.contains("@"), "detail echoed credential text: $detail")
        assertTrue(!detail.contains("engine14.invalid"), "detail echoed a host: $detail")
    }

    @Test fun failureDetailsNeverReflectSyntheticMarkersFromPlaylistFields() {
        val marker = "SYNTHETIC-URL-MARKER-7B2"
        val url = "https://engine14.invalid/live.m3u8"
        val keyUrl = "https://engine14.invalid/k.bin"

        val keyFormatSource = RecordingSource(
            listOf(LoadedHlsPlaylist(url, HlsMediaPlaylist(1, 4, true, listOf(
                HlsSegment(
                    1, "s.ts", 4.0, null, false, "AES-128", "k.bin", null,
                    keyFormat = "$url/$marker/format",
                ),
            )))),
            emptyMap(),
            emptyMap(),
        )
        val malformedIvSource = RecordingSource(
            listOf(LoadedHlsPlaylist(url, HlsMediaPlaylist(1, 4, true, listOf(
                HlsSegment(1, "s.ts", 4.0, null, false, "AES-128", "k.bin", null, keyIv = "0x1234"),
            )))),
            emptyMap(),
            mapOf(keyUrl to key(0)),
        )
        val first = LoadedHlsPlaylist(url, HlsMediaPlaylist(
            5, 4, false,
            listOf(HlsSegment(6, "6.ts", 4.0, null, false, "NONE", null, null)),
        ))
        val jumped = LoadedHlsPlaylist(url, HlsMediaPlaylist(
            9, 4, false,
            listOf(HlsSegment(9, "$url/$marker/9.ts", 4.0, null, false, "NONE", null, null)),
        ))
        val gapSource = RecordingSource(
            listOf(first, jumped),
            mapOf("https://engine14.invalid/6.ts" to byteArrayOf(6)),
            emptyMap(),
        )

        val details = listOf(
            record(url, keyFormatSource).detail.orEmpty(),
            record(url, malformedIvSource).detail.orEmpty(),
            record(url, gapSource).detail.orEmpty(),
        )
        assertTrue(details.all { it.contains("not supported") || it.contains("malformed") || it.contains("gap") })
        assertTrue(details.all { !it.contains(marker) && !it.contains("://") && !it.contains("@") }, "$details")
    }

    @Test fun sequenceAtLongMaxValueIsAcceptedOnceWithoutOverflow() {
        val url = "https://engine14.invalid/vod.m3u8"
        val source = RecordingSource(
            listOf(LoadedHlsPlaylist(url, HlsMediaPlaylist(
                Long.MAX_VALUE, 4, true,
                listOf(HlsSegment(Long.MAX_VALUE, "last.ts", 4.0, null, false, "NONE", null, null)),
            ))),
            mapOf("https://engine14.invalid/last.ts" to byteArrayOf(7, 7)),
            emptyMap(),
        )
        val output = ByteArrayOutputStream()
        val result = record(url, source, output)

        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(Long.MAX_VALUE, result.lastSequence)
        assertEquals(2L, result.bytesWritten)
        assertTrue(byteArrayOf(7, 7).contentEquals(output.toByteArray()))
    }
}

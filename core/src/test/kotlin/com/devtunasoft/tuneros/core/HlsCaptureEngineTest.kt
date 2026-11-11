package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlsCaptureEngineTest {
    private class FakeSource(
        playlists: Map<String, List<LoadedHlsPlaylist>>,
        private val segmentBytes: Map<String, ByteArray> = emptyMap(),
    ) : HlsCaptureSource {
        private val queues = playlists.mapValues { ArrayDeque(it.value) }
        val loadedUrls = ArrayList<String>()
        val requestedAfter = ArrayList<Long?>()
        val copied = ArrayList<Pair<String, HlsByteRange?>>()

        override fun loadPlaylist(
            url: String,
            afterSequenceExclusive: Long?,
        ): LoadedHlsPlaylist {
            loadedUrls += url
            requestedAfter += afterSequenceExclusive
            val queue = queues[url] ?: error("No fake playlist for $url")
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url to byteRange
            if (cancelled()) return StreamRecorder.Result(0, StreamRecorder.StopReason.CANCELLED)
            val bytes = segmentBytes[url] ?: byteArrayOf(1)
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    @Test fun masterChoosesHighestBandwidthAndLiveStartsAtEdge() {
        val masterUrl = "https://example.test/master.m3u8"
        val highUrl = "https://example.test/high/live.m3u8"
        val master = LoadedHlsPlaylist(
            masterUrl,
            HlsMasterPlaylist(
                listOf(
                    HlsVariant("low/live.m3u8", 500_000),
                    HlsVariant("high/live.m3u8", 2_000_000),
                ),
            ),
        )
        val first = LoadedHlsPlaylist(
            highUrl,
            HlsMediaPlaylist(
                mediaSequence = 10,
                targetDurationSeconds = 4,
                endList = false,
                segments = listOf(
                    HlsSegment(10, "10.ts", 4.0, null, false, "NONE", null, null),
                    HlsSegment(11, "11.ts", 4.0, null, false, "NONE", null, null),
                ),
            ),
        )
        val second = LoadedHlsPlaylist(
            highUrl,
            HlsMediaPlaylist(
                mediaSequence = 10,
                targetDurationSeconds = 4,
                endList = true,
                segments = listOf(
                    HlsSegment(10, "10.ts", 4.0, null, false, "NONE", null, null),
                    HlsSegment(11, "11.ts", 4.0, null, false, "NONE", null, null),
                    HlsSegment(12, "12.ts", 4.0, null, false, "NONE", null, null),
                ),
            ),
        )
        val source = FakeSource(
            playlists = mapOf(
                masterUrl to listOf(master),
                highUrl to listOf(first, second),
            ),
            segmentBytes = mapOf(
                "https://example.test/high/11.ts" to "B".toByteArray(),
                "https://example.test/high/12.ts" to "C".toByteArray(),
            ),
        )
        val output = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(
            initialUrl = masterUrl,
            output = output,
            endEpochSeconds = 1_000,
            source = source,
            cancelled = { false },
            nowEpochSeconds = { 100 },
            sleepMillis = {},
        )

        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(12L, result.lastSequence)
        assertEquals("BC", output.toString())
        assertTrue(source.loadedUrls.contains(highUrl))
        assertTrue(source.loadedUrls.none { it.contains("/low/") })
        assertEquals(listOf(null, null, 11L), source.requestedAfter)
    }

    @Test fun encryptedPlaylistFailsBeforeWritingMedia() {
        val url = "https://example.test/live.m3u8"
        val source = FakeSource(
            mapOf(
                url to listOf(
                    LoadedHlsPlaylist(
                        url,
                        HlsMediaPlaylist(
                            mediaSequence = 1,
                            targetDurationSeconds = 4,
                            endList = true,
                            segments = listOf(
                                HlsSegment(
                                    1, "1.ts", 4.0, null, false,
                                    "AES-128", "key.bin", null,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val output = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(
            url, output, 1_000, source, { false }, { 100 }, {},
        )
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals(0, output.size())
        assertTrue(result.detail.orEmpty().contains("AES-128"))
    }

    @Test fun initializationMapIsRejectedUntilFmp4WriterExists() {
        val url = "https://example.test/live.m3u8"
        val source = FakeSource(
            mapOf(
                url to listOf(
                    LoadedHlsPlaylist(
                        url,
                        HlsMediaPlaylist(
                            1, 4, true,
                            listOf(HlsSegment(1, "1.m4s", 4.0, null, false, "NONE", null, "init.mp4")),
                        ),
                    ),
                ),
            ),
        )
        val result = HlsCaptureEngine().record(
            url, ByteArrayOutputStream(), 1_000, source, { false }, { 100 }, {},
        )
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("fMP4"))
    }

    @Test fun byteRangeIsPassedToSegmentSource() {
        val url = "https://example.test/live.m3u8"
        val range = HlsByteRange(5, 10)
        val source = FakeSource(
            mapOf(
                url to listOf(
                    LoadedHlsPlaylist(
                        url,
                        HlsMediaPlaylist(
                            1, 4, true,
                            listOf(HlsSegment(1, "media.ts", 4.0, range, false, "NONE", null, null)),
                        ),
                    ),
                ),
            ),
            mapOf("https://example.test/media.ts" to ByteArray(5) { 3 }),
        )
        val output = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(
            url, output, 1_000, source, { false }, { 100 }, {},
        )
        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(range, source.copied.single().second)
        assertEquals(5, output.size())
    }

    @Test fun repeatedNonAdvancingPlaylistStopsAsStalled() {
        val url = "https://example.test/live.m3u8"
        val source = FakeSource(
            mapOf(
                url to listOf(
                    LoadedHlsPlaylist(url, HlsMediaPlaylist(50, 2, false, emptyList())),
                ),
            ),
        )
        var sleeps = 0
        val result = HlsCaptureEngine(maxStalledPolls = 2).record(
            url,
            ByteArrayOutputStream(),
            1_000,
            source,
            { false },
            { 100 },
            { sleeps++ },
        )
        assertEquals(HlsCaptureEngine.StopReason.STALLED, result.stopReason)
        assertEquals(1, sleeps)
    }

    @Test fun httpsPlaylistCannotDowngradeSegmentToHttp() {
        val url = "https://example.test/live.m3u8"
        val source = FakeSource(
            mapOf(
                url to listOf(
                    LoadedHlsPlaylist(
                        url,
                        HlsMediaPlaylist(
                            1, 4, true,
                            listOf(
                                HlsSegment(
                                    1, "http://example.test/1.ts", 4.0,
                                    null, false, "NONE", null, null,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = runCatching {
            HlsCaptureEngine().record(
                url, ByteArrayOutputStream(), 1_000, source, { false }, { 100 }, {},
            )
        }
        assertTrue(result.isFailure)
    }
    @Test fun masterWithSeparateAudioFailsClosed() {
        val masterUrl = "https://example.test/master.m3u8"
        val source = FakeSource(
            mapOf(
                masterUrl to listOf(
                    LoadedHlsPlaylist(
                        masterUrl,
                        HlsMasterPlaylist(
                            variants = listOf(
                                HlsVariant(
                                    uri = "video.m3u8",
                                    bandwidth = 2_000_000,
                                    audioGroup = "aud",
                                ),
                            ),
                            externalAudioGroups = setOf("aud"),
                        ),
                    ),
                ),
            ),
        )
        val result = HlsCaptureEngine().record(
            masterUrl, ByteArrayOutputStream(), 1_000, source, { false }, { 100 }, {},
        )
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("separate audio/video"))
        assertTrue(source.copied.isEmpty())
    }

    @Test fun missingLiveSequenceStopsAsGapInsteadOfSilentlySkipping() {
        val url = "https://example.test/live.m3u8"
        val first = LoadedHlsPlaylist(
            url,
            HlsMediaPlaylist(
                10, 4, false,
                listOf(
                    HlsSegment(10, "10.ts", 4.0, null, false, "NONE", null, null),
                    HlsSegment(11, "11.ts", 4.0, null, false, "NONE", null, null),
                ),
            ),
        )
        val jumped = LoadedHlsPlaylist(
            url,
            HlsMediaPlaylist(
                13, 4, false,
                listOf(
                    HlsSegment(13, "13.ts", 4.0, null, false, "NONE", null, null),
                ),
            ),
        )
        val source = FakeSource(
            playlists = mapOf(url to listOf(first, jumped)),
            segmentBytes = mapOf("https://example.test/11.ts" to byteArrayOf(11)),
        )
        val result = HlsCaptureEngine().record(
            url, ByteArrayOutputStream(), 1_000, source, { false }, { 100 }, {},
        )
        assertEquals(HlsCaptureEngine.StopReason.GAP, result.stopReason)
        assertEquals(11L, result.lastSequence)
        assertTrue(result.detail.orEmpty().contains("expected 12"))
    }


    @Test fun extremeTargetDurationUsesBoundedPollingInterval() {
        val url = "https://example.test/live.m3u8"
        val source = FakeSource(
            mapOf(
                url to listOf(
                    LoadedHlsPlaylist(
                        url,
                        HlsMediaPlaylist(
                            mediaSequence = 1,
                            targetDurationSeconds = Long.MAX_VALUE,
                            endList = false,
                            segments = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val result = HlsCaptureEngine(maxStalledPolls = 2).record(
            url, ByteArrayOutputStream(), 1_000, source,
            { false }, { 100 }, { delays += it },
        )
        assertEquals(HlsCaptureEngine.StopReason.STALLED, result.stopReason)
        assertEquals(listOf(10_000L), delays)
    }

    @Test fun incompleteByteRangeStopsAsPartialInsteadOfAcceptingCorruptMedia() {
        val url = "https://example.test/live.m3u8"
        val range = HlsByteRange(length = 5L, offset = 20L)
        val source = FakeSource(
            mapOf(
                url to listOf(
                    LoadedHlsPlaylist(
                        url,
                        HlsMediaPlaylist(
                            mediaSequence = 7,
                            targetDurationSeconds = 4,
                            endList = true,
                            segments = listOf(
                                HlsSegment(7, "media.ts", 4.0, range, false, "NONE", null, null),
                            ),
                        ),
                    ),
                ),
            ),
            segmentBytes = mapOf("https://example.test/media.ts" to byteArrayOf(1, 2, 3)),
        )
        val output = ByteArrayOutputStream()
        val result = HlsCaptureEngine().record(
            url, output, 1_000, source, { false }, { 100 }, {},
        )
        assertEquals(HlsCaptureEngine.StopReason.GAP, result.stopReason)
        assertEquals(null, result.lastSequence)
        assertEquals(3L, result.bytesWritten)
        assertEquals(3, output.size())
        assertTrue(result.detail.orEmpty().contains("expected 5 bytes"))
    }
}

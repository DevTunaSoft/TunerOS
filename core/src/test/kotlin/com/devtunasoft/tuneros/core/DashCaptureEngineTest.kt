package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashCaptureEngineTest {
    private val manifestUrl = "https://example.test/dash/manifest.mpd"

    private val videoSet = """    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" duration="3000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="chunk-@RepresentationID@-@Number%05d@.m4s"/>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>"""

    private fun mpd(duration: String = "PT9S", type: String = "static", baseUrl: String? = null): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" mediaPresentationDuration="$duration">
${baseUrl?.let { "  <BaseURL>$it</BaseURL>\n" }.orEmpty()}  <Period id="0" start="PT0S">
$videoSet
  </Period>
</MPD>
""".replace('@', '$')

    /** Serves fixed bytes per URL and records every request. [shortAt] makes one segment report LOW_STORAGE. */
    private class FakeDashSource(
        private val manifest: String,
        private val segments: Map<String, ByteArray> = emptyMap(),
        private val manifestUrl: String,
        private val lowStorageAt: String? = null,
    ) : DashCaptureSource {
        val copied = ArrayList<String>()

        override fun loadManifest(url: String): LoadedDashManifest = LoadedDashManifest(manifestUrl, manifest)

        override fun copySegment(
            url: String,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            if (cancelled()) return StreamRecorder.Result(0, StreamRecorder.StopReason.CANCELLED)
            if (url == lowStorageAt) return StreamRecorder.Result(0, StreamRecorder.StopReason.LOW_STORAGE)
            val bytes = segments[url] ?: error("No fake segment for $url")
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    private val segments = mapOf(
        "https://example.test/dash/init-v1.m4s" to byteArrayOf(9, 9),
        "https://example.test/dash/chunk-v1-00001.m4s" to byteArrayOf(1),
        "https://example.test/dash/chunk-v1-00002.m4s" to byteArrayOf(2),
        "https://example.test/dash/chunk-v1-00003.m4s" to byteArrayOf(3),
    )

    private fun record(
        source: FakeDashSource,
        out: ByteArrayOutputStream = ByteArrayOutputStream(),
        cancelled: () -> Boolean = { false },
        now: Long = 100,
        end: Long = 1_000,
    ): DashCaptureEngine.Result = DashCaptureEngine().record(
        manifestUrl, out, end, source, cancelled, { now },
    )

    @Test fun staticDashWritesTheInitializationThenEverySegmentInOrder() {
        val source = FakeDashSource(mpd(), segments, manifestUrl)
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(listOf(9, 9, 1, 2, 3), out.toByteArray().map { it.toInt() })
        assertEquals(5L, result.bytesWritten)
        assertEquals(
            listOf(
                "https://example.test/dash/init-v1.m4s",
                "https://example.test/dash/chunk-v1-00001.m4s",
                "https://example.test/dash/chunk-v1-00002.m4s",
                "https://example.test/dash/chunk-v1-00003.m4s",
            ),
            source.copied,
        )
    }

    @Test fun segmentCountIgnoresAFewMillisecondsPastAWholeSegment() {
        val source = FakeDashSource(mpd(duration = "PT9.033S"), segments, manifestUrl)
        record(source)
        assertEquals(4, source.copied.size, "init plus three segments")
    }

    @Test fun baseUrlIsResolvedAgainstTheManifest() {
        val relocated = mapOf(
            "https://example.test/dash/segments/init-v1.m4s" to byteArrayOf(9),
            "https://example.test/dash/segments/chunk-v1-00001.m4s" to byteArrayOf(1),
            "https://example.test/dash/segments/chunk-v1-00002.m4s" to byteArrayOf(2),
            "https://example.test/dash/segments/chunk-v1-00003.m4s" to byteArrayOf(3),
        )
        val source = FakeDashSource(mpd(baseUrl = "segments/"), relocated, manifestUrl)
        val out = ByteArrayOutputStream()
        assertEquals(DashCaptureEngine.StopReason.END_LIST, record(source, out).stopReason)
        assertEquals(listOf(9, 1, 2, 3), out.toByteArray().map { it.toInt() })
    }

    @Test fun liveDashIsRefusedBeforeAnyByteIsWritten() {
        val source = FakeDashSource(mpd(type = "dynamic"), segments, manifestUrl)
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("Live DASH recording is not supported yet", result.detail)
        assertEquals(0, out.size())
        assertEquals(emptyList(), source.copied)
    }

    @Test fun cancellationBeforeTheFirstSegmentCopiesNothing() {
        val source = FakeDashSource(mpd(), segments, manifestUrl)
        val result = record(source, cancelled = { true })
        assertEquals(DashCaptureEngine.StopReason.CANCELLED, result.stopReason)
        assertEquals(emptyList(), source.copied)
    }

    @Test fun reachingTheScheduledStopBeforeTheFirstSegmentIsADeadline() {
        val source = FakeDashSource(mpd(), segments, manifestUrl)
        val result = record(source, now = 1_000, end = 1_000)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(emptyList(), source.copied)
    }

    @Test fun anEmptyInitializationFailsClosed() {
        val withEmptyInit = segments + ("https://example.test/dash/init-v1.m4s" to byteArrayOf())
        val source = FakeDashSource(mpd(), withEmptyInit, manifestUrl)
        val result = record(source)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("empty"))
        assertEquals(listOf("https://example.test/dash/init-v1.m4s"), source.copied)
    }

    @Test fun lowStorageKeepsTheBytesAlreadyWritten() {
        val source = FakeDashSource(
            mpd(), segments, manifestUrl, lowStorageAt = "https://example.test/dash/chunk-v1-00002.m4s",
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.LOW_STORAGE, result.stopReason)
        assertEquals(listOf(9, 9, 1), out.toByteArray().map { it.toInt() })
        assertEquals(3L, result.bytesWritten)
    }

    @Test fun anAbsurdSegmentCountIsRefusedInsteadOfLoopingUntilTheDeadline() {
        val source = FakeDashSource(mpd(duration = "PT1000000S").replace("duration=\"3000\"", "duration=\"1000\""), segments, manifestUrl)
        val result = record(source)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("too many"))
        assertEquals(emptyList(), source.copied)
    }
}

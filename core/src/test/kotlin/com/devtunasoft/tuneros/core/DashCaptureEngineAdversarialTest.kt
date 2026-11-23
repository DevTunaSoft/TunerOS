package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial, deterministic JVM coverage of [DashCaptureEngine] on scratch data. All addresses use the reserved
 * example.test domain; no manifest, URL, credential or payload from a provider appears here.
 */
class DashCaptureEngineAdversarialTest {
    private val manifestUrl = "https://scratch.example.test/dash/manifest.mpd"
    private val initUrl = "https://scratch.example.test/dash/init.m4s"
    private val segmentUrl = "https://scratch.example.test/dash/seg-00001.m4s"

    private val singleFileUrl = "https://scratch.example.test/dash/single.mp4"

    private fun templateManifest(
        count: Int,
        segmentSeconds: Int = 3,
        base: String? = null,
        extra: String = "",
    ): String {
        val baseLine = base?.let { "  <BaseURL>$it</BaseURL>\n" }.orEmpty()
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT${count * segmentSeconds}S">
$baseLine  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" duration="${segmentSeconds * 1000}" startNumber="1" initialization="init.m4s" media="seg-@Number%05d@.m4s"/>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>
$extra  </Period>
</MPD>
""".replace('@', '$')
    }

    private fun singleFileListManifest(initRange: String, ranges: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT3S">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.64000a" width="160" height="120">
        <BaseURL>single.mp4</BaseURL>
        <SegmentList timescale="1000" duration="3000" startNumber="1">
          <Initialization range="$initRange"/>
          $ranges
        </SegmentList>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""

    private fun segmentBaseManifest(indexRange: String, initRange: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT3S">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.64000a" width="160" height="120">
        <BaseURL>single.mp4</BaseURL>
        <SegmentBase indexRange="$indexRange"><Initialization range="$initRange"/></SegmentBase>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""

    private fun listedLiveManifest(): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="1970-01-01T00:16:40Z" minimumUpdatePeriod="PT2S">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120">
        <SegmentList timescale="1000" duration="3000" startNumber="1">
          <Initialization sourceURL="init.m4s"/>
          <SegmentURL media="seg-00003.m4s"/>
          <SegmentURL media="seg-00004.m4s"/>
        </SegmentList>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""

    /** Serves scratch addresses on demand and lets any copy report an adversarial byte count. */
    private class ReportedCountSource(
        private val manifest: String,
        private val manifestUrl: String,
        private val segments: Map<String, ByteArray> = emptyMap(),
        private val counts: Map<String, Long> = emptyMap(),
        private val missingOn: String? = null,
    ) : DashCaptureSource {
        val copied = ArrayList<String>()

        override fun loadManifest(url: String): LoadedDashManifest = LoadedDashManifest(manifestUrl, manifest)

        override fun loadRange(url: String, range: HlsByteRange): ByteArray = error("no index in this fixture")

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            if (url == missingOn) throw DashSegmentNotPublishedException()
            val bytes = segments[url] ?: byteArrayOf(1)
            val count = counts[url] ?: bytes.size.toLong()
            output.write(bytes)
            onProgress(count)
            return StreamRecorder.Result(count, StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    /** Serves a bounded byte range of one scratch file and records every request. */
    private class RangeSource(
        private val manifest: String,
        private val manifestUrl: String,
        private val resources: Map<String, ByteArray>,
    ) : DashCaptureSource {
        val requests = ArrayList<Pair<String, HlsByteRange?>>()

        override fun loadManifest(url: String): LoadedDashManifest = LoadedDashManifest(manifestUrl, manifest)

        override fun loadRange(url: String, range: HlsByteRange): ByteArray {
            val resource = resources[url] ?: error("No scratch resource for the requested address")
            val from = range.offset.toInt()
            return resource.copyOfRange(from, minOf(resource.size, from + range.length.toInt()))
        }

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            requests += url to byteRange
            val resource = resources[url] ?: error("No scratch resource for the requested address")
            val from = byteRange?.offset?.toInt() ?: 0
            val to = if (byteRange == null) resource.size else minOf(resource.size, from + byteRange.length.toInt())
            val bytes = resource.copyOfRange(from, to)
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    private class TrackingOutputStream : OutputStream() {
        var closed = false
            private set

        override fun write(byte: Int) = Unit

        override fun close() {
            closed = true
        }
    }

    private fun record(
        manifest: String,
        source: DashCaptureSource,
        out: OutputStream = ByteArrayOutputStream(),
        end: Long = 1_000L,
        now: () -> Long = { 100L },
        sleep: (Long) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): DashCaptureEngine.Result = DashCaptureEngine().record(
        manifestUrl, out, end, source, { false }, now, sleep, onProgress,
    )

    @Test fun anOverflowingReportedByteCountFailsClosedInsteadOfThrowing() {
        val manifest = templateManifest(2)
        val source = ReportedCountSource(
            manifest, manifestUrl,
            mapOf(initUrl to byteArrayOf(9), segmentUrl to byteArrayOf(1)),
            mapOf(segmentUrl to Long.MAX_VALUE),
        )
        val out = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        val result = record(manifest, source, out, onProgress = { progress += it })
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals(1L, result.bytesWritten)
        assertTrue(result.detail.orEmpty().contains("byte count"), "detail=${result.detail}")
        assertTrue(progress.all { it >= 0L }, "progress went negative: $progress")
        assertTrue(Long.MAX_VALUE in progress, "progress did not saturate: $progress")
        assertEquals(listOf(initUrl, segmentUrl), source.copied)
    }

    @Test fun aNegativeReportedByteCountFailsClosedWithoutNegativeTotals() {
        val manifest = templateManifest(2)
        val source = ReportedCountSource(
            manifest, manifestUrl,
            mapOf(initUrl to byteArrayOf(9), segmentUrl to byteArrayOf(1)),
            mapOf(segmentUrl to -1L),
        )
        val progress = ArrayList<Long>()
        val result = record(manifest, source, onProgress = { progress += it })
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH byte count is invalid", result.detail)
        assertEquals(1L, result.bytesWritten)
        assertTrue(progress.all { it >= 0L }, "progress went negative: $progress")
    }

    @Test fun aByteCountThatExactlyFillsTheLongBudgetIsAccepted() {
        val only = "https://scratch.example.test/dash/seg-00001.m4s"
        val manifest = templateManifest(1)
        val source = ReportedCountSource(
            manifest, manifestUrl,
            mapOf(initUrl to byteArrayOf(9), only to byteArrayOf(1)),
            mapOf(initUrl to Long.MAX_VALUE - 1, only to 1L),
        )
        val result = record(manifest, source)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(Long.MAX_VALUE, result.bytesWritten)
    }

    @Test fun aLiveListingWithAnOverflowingByteCountAlsoFailsClosed() {
        val edge = "https://scratch.example.test/dash/seg-00004.m4s"
        val manifest = listedLiveManifest()
        val source = ReportedCountSource(
            manifest, manifestUrl,
            mapOf(initUrl to byteArrayOf(9), edge to byteArrayOf(4)),
            mapOf(edge to Long.MAX_VALUE),
        )
        val result = record(manifest, source, end = 2_000L, now = { 1_000L })
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH byte count is invalid", result.detail)
        assertEquals(1L, result.bytesWritten)
        assertEquals(listOf(initUrl, edge), source.copied)
    }

    @Test fun repeatedCaptureCyclesKeepNoRetainedBuffersOrGrowth() {
        val count = 200
        val manifest = templateManifest(count, segmentSeconds = 1)
        val engine = DashCaptureEngine()
        repeat(12) {
            val source = ReportedCountSource(manifest, manifestUrl)
            val out = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            val result = engine.record(manifestUrl, out, 1_000L, source, { false }, { 100L }, { }, { progress += it })
            assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
            assertEquals((count + 1).toLong(), result.bytesWritten)
            assertEquals(count + 1, out.size())
            assertEquals(2 * (count + 1), progress.size, "one source and one engine progress event per copy")
            assertEquals(result.bytesWritten, progress.last())
            assertEquals(count + 1, source.copied.size)
        }
    }

    @Test fun aSingleReferenceSegmentBaseIndexIsRecordedInFull() {
        val initialization = ByteArray(10) { 0x10.toByte() }
        val index = SidxBox.build(references = listOf(8L to 2_000L))
        val fragment = ByteArray(8) { 0x41.toByte() }
        val manifest = segmentBaseManifest("10-${9 + index.size}", "0-9")
        val source = RangeSource(manifest, manifestUrl, mapOf(singleFileUrl to initialization + index + fragment))
        val out = ByteArrayOutputStream()
        val result = record(manifest, source, out)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals((initialization + fragment).toList(), out.toByteArray().toList())
        assertEquals(18L, result.bytesWritten)
        assertEquals(2, source.requests.size)
    }

    @Test fun anEmptySegmentBaseIndexFailsClosedBeforeAnyByteIsWritten() {
        val index = SidxBox.build(references = emptyList())
        val manifest = segmentBaseManifest("10-${9 + index.size}", "0-9")
        val source = RangeSource(manifest, manifestUrl, mapOf(singleFileUrl to ByteArray(10) + index))
        val out = ByteArrayOutputStream()
        val result = record(manifest, source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH SegmentBase index has no references", result.detail)
        assertEquals(0, out.size())
        assertEquals(emptyList<Pair<String, HlsByteRange?>>(), source.requests)
    }

    @Test fun aTruncatedSegmentBaseIndexFailsClosedBeforeAnyByteIsWritten() {
        val full = SidxBox.build(references = listOf(8L to 2_000L, 8L to 2_000L))
        // The manifest asks for the whole box, but the origin only answers its first 12 bytes.
        val truncated = full.copyOf(12)
        val manifest = segmentBaseManifest("10-${9 + full.size}", "0-9")
        val source = RangeSource(manifest, manifestUrl, mapOf(singleFileUrl to ByteArray(10) + truncated))
        val out = ByteArrayOutputStream()
        val result = record(manifest, source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH SegmentBase index range is not the bytes requested", result.detail)
        assertEquals(0, out.size())
        assertEquals(emptyList<Pair<String, HlsByteRange?>>(), source.requests)
    }

    @Test fun anOversizedSegmentBaseIndexRangeIsRefusedWithoutReadingIt() {
        val manifest = segmentBaseManifest("0-1000000", "0-9")
        val source = RangeSource(manifest, manifestUrl, mapOf(singleFileUrl to ByteArray(2_000_000)))
        val result = record(manifest, source)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH SegmentBase index is too large", result.detail)
        assertEquals(emptyList<Pair<String, HlsByteRange?>>(), source.requests)
    }

    @Test fun aTruncatedInitializationRangeStopsBeforeAnyMediaIsRequested() {
        val manifest = singleFileListManifest("0-9", """<SegmentURL mediaRange="10-14"/>""")
        val source = RangeSource(manifest, manifestUrl, mapOf(singleFileUrl to ByteArray(5) { it.toByte() }))
        val out = ByteArrayOutputStream()
        val result = record(manifest, source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("byte range incomplete"), "detail=${result.detail}")
        assertEquals(5L, result.bytesWritten)
        assertEquals(5, out.size())
        assertEquals(1, source.requests.size, "only the initialization was requested")
    }

    @Test fun aTruncatedMediaRangeStopsWithoutRequestingLaterSegments() {
        val ranges = """<SegmentURL mediaRange="10-19"/><SegmentURL mediaRange="20-29"/><SegmentURL mediaRange="30-39"/>"""
        val manifest = singleFileListManifest("0-9", ranges)
        val source = RangeSource(manifest, manifestUrl, mapOf(singleFileUrl to ByteArray(25) { it.toByte() }))
        val result = record(manifest, source)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("byte range incomplete"), "detail=${result.detail}")
        assertEquals(25L, result.bytesWritten)
        assertEquals(3, source.requests.size, "initialization and two media ranges, not the third")
    }

    @Test fun rejectedCapturesLeaveNoFilesThreadsOrClosedOutputStream() {
        val scratch: Path = Files.createTempDirectory("dash-a22-scratch")
        val threadsBefore = Thread.getAllStackTraces().keys.map { it.name }.toSet()
        try {
            repeat(10) {
                val out = TrackingOutputStream()
                val manifest = templateManifest(2)
                val result = record(
                    manifest, ReportedCountSource(manifest, manifestUrl, counts = mapOf(segmentUrl to Long.MAX_VALUE)), out,
                )
                assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
                assertFalse(out.closed, "the caller's output stream must stay open")
            }
            val added = Thread.getAllStackTraces().keys.map { it.name }.toSet() - threadsBefore
            assertFalse(
                added.any { it.contains("dash", ignoreCase = true) || it.contains("capture", ignoreCase = true) },
                "unexpected capture threads: $added",
            )
            assertEquals(emptyList<Path>(), Files.list(scratch).use { it.toList() }, "no scratch file was created")
        } finally {
            Files.deleteIfExists(scratch)
        }
    }

    @Test fun unsafeRelativeBasesAreRefusedBeforeAnyByteIsWritten() {
        for (base in listOf("http://scratch.example.test/downgrade/", "file:///scratch/secret", "ftp://scratch.example.test/", "data:text/plain,scratch")) {
            val manifest = templateManifest(1, base = base)
            val source = ReportedCountSource(manifest, manifestUrl)
            val out = ByteArrayOutputStream()
            val result = record(manifest, source, out)
            assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason, "base=$base")
            assertEquals("DASH resource must use HTTP or HTTPS", result.detail)
            assertEquals(0, out.size())
            assertEquals(emptyList(), source.copied)
        }
    }

    @Test fun relativeTraversalResolvesOnTheHttpHostAndNeverBecomesAFilePath() {
        val manifest = templateManifest(2, base = "../other/../segments/")
        val source = object : DashCaptureSource {
            val urls = ArrayList<String>()

            override fun loadManifest(url: String): LoadedDashManifest = LoadedDashManifest(manifestUrl, manifest)
            override fun loadRange(url: String, range: HlsByteRange): ByteArray = error("no index in this fixture")
            override fun copySegment(
                url: String, byteRange: HlsByteRange?, output: OutputStream,
                cancelled: () -> Boolean, onProgress: (Long) -> Unit,
            ): StreamRecorder.Result {
                urls += url
                output.write(1)
                onProgress(1L)
                return StreamRecorder.Result(1L, StreamRecorder.StopReason.END_OF_STREAM)
            }
        }
        val result = record(manifest, source)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(3, source.urls.size)
        assertTrue(source.urls.all { it.startsWith("https://scratch.example.test/segments/") }, "urls=${source.urls}")
    }

    @Test fun failureDetailsNeverCarryAddressesMarkersOrPayloadText() {
        val markers = listOf("scratch.example.test", "SECRET_SCRATCH_MARKER", "PAYLOAD_SCRATCH_MARKER", manifestUrl)
        val protectedManifest = templateManifest(
            1, base = "http://SECRET_SCRATCH_MARKER.invalid/", extra = "    <!-- PAYLOAD_SCRATCH_MARKER -->\n",
        )
        val truncatedManifest = singleFileListManifest("0-9", """<SegmentURL mediaRange="10-19"/>""")
        val results = listOf(
            record(templateManifest(2), ReportedCountSource(templateManifest(2), manifestUrl, missingOn = segmentUrl)),
            record(truncatedManifest, RangeSource(truncatedManifest, manifestUrl, mapOf(singleFileUrl to ByteArray(2)))),
            record(templateManifest(2), ReportedCountSource(templateManifest(2), manifestUrl, counts = mapOf(segmentUrl to Long.MAX_VALUE))),
            record(protectedManifest, ReportedCountSource(protectedManifest, manifestUrl)),
        )
        assertEquals(4, results.size)
        results.forEach { result ->
            assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
            val detail = result.detail.orEmpty()
            markers.forEach { marker ->
                assertFalse(detail.contains(marker, ignoreCase = true), "detail leaked a marker: $detail")
            }
        }
    }
}

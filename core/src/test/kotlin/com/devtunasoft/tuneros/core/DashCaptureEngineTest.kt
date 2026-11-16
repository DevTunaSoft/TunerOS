package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashCaptureEngineTest {
    private val manifestUrl = "https://example.test/dash/manifest.mpd"
    private val initUrl = "https://example.test/dash/init-v1.m4s"

    /** Live fixtures: 3-second segments, and segment N is complete at 1000 + 3N seconds (the epoch below). */
    private val liveStart = "1970-01-01T00:16:40Z"

    private val videoSet = """    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" duration="3000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="chunk-@RepresentationID@-@Number%05d@.m4s"/>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>"""

    /** A video set whose SegmentTemplate carries [timeline] in place of a fixed duration. */
    private fun timelineSet(timeline: String, media: String = "chunk-@Time@.m4s"): String = """    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="$media">
        <SegmentTimeline>$timeline</SegmentTimeline>
      </SegmentTemplate>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>"""

    private fun mpd(
        duration: String = "PT9S",
        type: String = "static",
        baseUrl: String? = null,
        availabilityStart: String? = null,
        body: String = videoSet,
    ): String {
        val timing = if (type == "dynamic") {
            availabilityStart?.let { " availabilityStartTime=\"$it\"" }.orEmpty()
        } else {
            " mediaPresentationDuration=\"$duration\""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type"$timing>
${baseUrl?.let { "  <BaseURL>$it</BaseURL>\n" }.orEmpty()}  <Period id="0" start="PT0S">
$body
  </Period>
</MPD>
""".replace('@', '$')
    }

    /** A test clock in whole seconds. It moves only when the engine sleeps, so live timing is deterministic. */
    private class FakeClock(seconds: Long) {
        var millis: Long = seconds * 1000

        fun now(): Long = millis / 1000

        fun sleep(delayMillis: Long) {
            millis += delayMillis
        }
    }

    /**
     * Serves fixed bytes per URL and records every request, including refused ones. [lowStorageAt] makes one
     * segment report LOW_STORAGE. [publishedAtSeconds] answers 404 until the clock reaches the given second.
     */
    private class FakeDashSource(
        private val manifest: String,
        private val segments: Map<String, ByteArray> = emptyMap(),
        private val manifestUrl: String,
        private val lowStorageAt: String? = null,
        private val clock: FakeClock? = null,
        private val publishedAtSeconds: Map<String, Long> = emptyMap(),
    ) : DashCaptureSource {
        val copied = ArrayList<String>()

        override fun loadManifest(url: String): LoadedDashManifest = LoadedDashManifest(manifestUrl, manifest)

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            if (cancelled()) return StreamRecorder.Result(0, StreamRecorder.StopReason.CANCELLED)
            val publishedAt = publishedAtSeconds[url]
            if (publishedAt != null && clock != null && clock.now() < publishedAt) {
                throw DashSegmentNotPublishedException()
            }
            if (url == lowStorageAt) return StreamRecorder.Result(0, StreamRecorder.StopReason.LOW_STORAGE)
            val bytes = segments[url] ?: error("No fake segment for $url")
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    private val segments = mapOf(
        initUrl to byteArrayOf(9, 9),
        "https://example.test/dash/chunk-v1-00001.m4s" to byteArrayOf(1),
        "https://example.test/dash/chunk-v1-00002.m4s" to byteArrayOf(2),
        "https://example.test/dash/chunk-v1-00003.m4s" to byteArrayOf(3),
    )

    private fun chunk(number: Int): String = "https://example.test/dash/chunk-v1-%05d.m4s".format(number)

    private val liveSegments: Map<String, ByteArray> =
        mapOf(initUrl to byteArrayOf(9, 9)) + (1..12).associate { chunk(it) to byteArrayOf(it.toByte()) }

    /** Every live segment N is published when its media time has elapsed: 1000 + 3N seconds. */
    private fun publishedOnTime(): Map<String, Long> = (1..12).associate { chunk(it) to 1_000L + 3L * it }

    /** Segment URLs addressed by time: segment N starts at 3000 * (N - 1) in a 1000-unit timeline. */
    private fun timeUrl(number: Int): String = "https://example.test/dash/chunk-${3000 * (number - 1)}.m4s"

    private val liveSegmentsByTime: Map<String, ByteArray> =
        mapOf(initUrl to byteArrayOf(9, 9)) + (1..12).associate { timeUrl(it) to byteArrayOf(it.toByte()) }

    private fun publishedOnTimeByTime(): Map<String, Long> = (1..12).associate { timeUrl(it) to 1_000L + 3L * it }

    private fun record(
        source: DashCaptureSource,
        out: ByteArrayOutputStream = ByteArrayOutputStream(),
        cancelled: () -> Boolean = { false },
        now: Long = 100,
        end: Long = 1_000,
        clock: FakeClock = FakeClock(now),
    ): DashCaptureEngine.Result = DashCaptureEngine().record(
        manifestUrl, out, end, source, cancelled, clock::now, clock::sleep,
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

    @Test fun liveDashWithoutAnAvailabilityStartIsRefusedBeforeAnyByteIsWritten() {
        val source = FakeDashSource(mpd(type = "dynamic"), liveSegments, manifestUrl)
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("Live DASH manifest needs a valid availabilityStartTime", result.detail)
        assertEquals(0, out.size())
        assertEquals(emptyList(), source.copied)
    }

    @Test fun liveRecordingStartsAtTheNewestCompleteSegmentAndStopsAtTheScheduledEnd() {
        val clock = FakeClock(1_010)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart), liveSegments, manifestUrl,
            clock = clock, publishedAtSeconds = publishedOnTime(),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_025, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5, 6, 7, 8), out.toByteArray().map { it.toInt() })
        assertEquals(8L, result.bytesWritten)
        assertEquals(listOf(initUrl) + (3..8).map { chunk(it) }, source.copied)
    }

    @Test fun liveRecordingWaitsForTheFirstCompleteSegmentWhenStartedEarly() {
        val clock = FakeClock(1_001)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart), liveSegments, manifestUrl,
            clock = clock, publishedAtSeconds = publishedOnTime(),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_004, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 1), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, chunk(1)), source.copied)
    }

    @Test fun aLiveSegmentNotPublishedYetIsRetriedUntilItAppears() {
        val clock = FakeClock(1_010)
        val published = publishedOnTime() + (chunk(4) to 1_014L)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart), liveSegments, manifestUrl,
            clock = clock, publishedAtSeconds = published,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_020, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5, 6), out.toByteArray().map { it.toInt() })
        assertTrue(source.copied.count { it == chunk(4) } >= 2, "segment 4 was retried after its 404")
    }

    @Test fun aLiveSegmentMissingPastTheGraceStopsWithoutWritingAGap() {
        val clock = FakeClock(1_010)
        val published = publishedOnTime() + (chunk(4) to Long.MAX_VALUE)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart), liveSegments, manifestUrl,
            clock = clock, publishedAtSeconds = published,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH live segment was not published in time", result.detail)
        assertEquals(listOf(9, 9, 3), out.toByteArray().map { it.toInt() })
        assertEquals(3L, result.bytesWritten)
        assertFalse(chunk(5) in source.copied, "no later segment is written past the gap")
    }

    @Test fun aLiveInitializationNotPublishedStopsBeforeAnyMediaIsWritten() {
        val clock = FakeClock(1_010)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart), liveSegments, manifestUrl,
            clock = clock, publishedAtSeconds = publishedOnTime() + (initUrl to Long.MAX_VALUE),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH initialization segment is not available", result.detail)
        assertEquals(0, out.size())
        assertEquals(listOf(initUrl), source.copied)
    }

    @Test fun cancellationWhileWaitingForALiveSegmentStopsWithoutRequestingIt() {
        val clock = FakeClock(1_001)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart), liveSegments, manifestUrl,
            clock = clock, publishedAtSeconds = publishedOnTime(),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, cancelled = { clock.now() >= 1_002 }, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.CANCELLED, result.stopReason)
        assertEquals(listOf(initUrl), source.copied)
        assertEquals(2L, result.bytesWritten)
    }

    @Test fun aStaticSegmentTheOriginNoLongerServesStopsWithTheBytesAlreadyWritten() {
        val clock = FakeClock(100)
        val source = FakeDashSource(
            mpd(), segments, manifestUrl, clock = clock, publishedAtSeconds = mapOf(chunk(2) to Long.MAX_VALUE),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH segment is not available", result.detail)
        assertEquals(listOf(9, 9, 1), out.toByteArray().map { it.toInt() })
        assertEquals(3L, result.bytesWritten)
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
        val withEmptyInit = segments + (initUrl to byteArrayOf())
        val source = FakeDashSource(mpd(), withEmptyInit, manifestUrl)
        val result = record(source)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("empty"))
        assertEquals(listOf(initUrl), source.copied)
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

    @Test fun staticTimelineWritesEverySegmentInOrderAddressedByTime() {
        val byTime = mapOf(
            initUrl to byteArrayOf(9, 9),
            timeUrl(1) to byteArrayOf(1),
            timeUrl(2) to byteArrayOf(2),
            timeUrl(3) to byteArrayOf(3),
        )
        val source = FakeDashSource(mpd(body = timelineSet("""<S t="0" d="3000" r="2"/>""")), byTime, manifestUrl)
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(listOf(9, 9, 1, 2, 3), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, timeUrl(1), timeUrl(2), timeUrl(3)), source.copied)
    }

    @Test fun openLiveTimelineFollowsTheClockAndAddressesSegmentsByTime() {
        val clock = FakeClock(1_010)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart, body = timelineSet("""<S t="0" d="3000" r="-1"/>""")),
            liveSegmentsByTime, manifestUrl, clock = clock, publishedAtSeconds = publishedOnTimeByTime(),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_025, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5, 6, 7, 8), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl) + (3..8).map { timeUrl(it) }, source.copied)
    }

    @Test fun aFiniteLiveTimelineWindowFollowsTheClockWithNumberAddressing() {
        val clock = FakeClock(1_010)
        val source = FakeDashSource(
            mpd(
                type = "dynamic",
                availabilityStart = liveStart,
                body = timelineSet("""<S t="0" d="3000" r="11"/>""", media = "chunk-@RepresentationID@-@Number%05d@.m4s"),
            ),
            liveSegments, manifestUrl, clock = clock, publishedAtSeconds = publishedOnTime(),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_025, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5, 6, 7, 8), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl) + (3..8).map { chunk(it) }, source.copied)
    }

    /** Media-time URLs of a timeline that starts at 600000 units, which a presentation offset maps to period time zero. */
    private fun offsetUrl(number: Int): String = "https://example.test/dash/chunk-${600000L + 3000L * (number - 1)}.m4s"

    @Test fun openLiveTimelineWithAPresentationOffsetFollowsThePresentationClock() {
        val clock = FakeClock(1_010)
        val segments = mapOf(initUrl to byteArrayOf(9, 9)) + (1..12).associate { offsetUrl(it) to byteArrayOf(it.toByte()) }
        val published = (1..12).associate { offsetUrl(it) to 1_000L + 3L * it }
        val source = FakeDashSource(
            mpd(
                type = "dynamic",
                availabilityStart = liveStart,
                body = timelineSet("""<S t="600000" d="3000" r="-1"/>""")
                    .replace("startNumber=\"1\"", "startNumber=\"1\" presentationTimeOffset=\"600000\""),
            ),
            segments, manifestUrl, clock = clock, publishedAtSeconds = published,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_025, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5, 6, 7, 8), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl) + (3..8).map { offsetUrl(it) }, source.copied)
    }

    /** Serves byte ranges of named resources and records every request with its range. */
    private class RangeDashSource(
        private val manifest: String,
        private val manifestUrl: String,
        private val resources: Map<String, ByteArray>,
    ) : DashCaptureSource {
        val requests = ArrayList<Pair<String, HlsByteRange?>>()

        override fun loadManifest(url: String): LoadedDashManifest = LoadedDashManifest(manifestUrl, manifest)

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            requests += url to byteRange
            if (cancelled()) return StreamRecorder.Result(0, StreamRecorder.StopReason.CANCELLED)
            val resource = resources[url] ?: error("No resource for $url")
            val from = byteRange?.offset?.toInt() ?: 0
            val to = if (byteRange == null) resource.size else minOf(resource.size, from + byteRange.length.toInt())
            val bytes = resource.copyOfRange(from, to)
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    private val singleFileUrl = "https://example.test/dash/single.mp4"
    private val singleFile = ByteArray(30) { it.toByte() }

    private fun singleFileManifest(initialization: String, ranges: String): String = mpd(body = """    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.64000a" width="160" height="120">
        <BaseURL>single.mp4</BaseURL>
        <SegmentList timescale="1000" duration="3000" startNumber="1">
          $initialization
          $ranges
        </SegmentList>
      </Representation>
    </AdaptationSet>""")

    @Test fun staticSegmentListCopiesTheInitializationAndEachRangeInOrder() {
        val source = RangeDashSource(
            singleFileManifest("""<Initialization range="0-9"/>""", """<SegmentURL mediaRange="10-19"/><SegmentURL mediaRange="20-29"/>"""),
            manifestUrl, mapOf(singleFileUrl to singleFile),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals((0 until 30).toList(), out.toByteArray().map { it.toInt() })
        assertEquals(30L, result.bytesWritten)
        assertEquals<List<Pair<String, HlsByteRange?>>>(
            listOf(
                singleFileUrl to HlsByteRange(length = 10, offset = 0),
                singleFileUrl to HlsByteRange(length = 10, offset = 10),
                singleFileUrl to HlsByteRange(length = 10, offset = 20),
            ),
            source.requests,
        )
    }

    @Test fun aShortRangeStopsAsUnsupportedKeepingTheBytesWritten() {
        // The second range asks for ten bytes at 20, but the resource ends at 25: five arrive, and no later range is read.
        val source = RangeDashSource(
            singleFileManifest("""<Initialization range="0-9"/>""", """<SegmentURL mediaRange="10-19"/><SegmentURL mediaRange="20-29"/><SegmentURL mediaRange="0-4"/>"""),
            manifestUrl, mapOf(singleFileUrl to singleFile.copyOf(25)),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("byte range"))
        assertEquals(25L, result.bytesWritten)
        assertEquals((0 until 25).toList(), out.toByteArray().map { it.toInt() })
        assertEquals(3, source.requests.size, "initialization and the first two ranges, not the third")
    }
}

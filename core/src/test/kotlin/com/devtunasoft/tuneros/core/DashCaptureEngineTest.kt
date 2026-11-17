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

        override fun loadRange(url: String, range: HlsByteRange): ByteArray = error("no index in this fixture")

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

    @Test fun openLiveTimelineWithSeveralRepeatsOfOneDurationFollowsTheClockLikeOneEntry() {
        val clock = FakeClock(1_010)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart, body = timelineSet("""<S t="0" d="3000" r="1"/><S d="3000" r="-1"/>""")),
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

        override fun loadRange(url: String, range: HlsByteRange): ByteArray {
            val resource = resources[url] ?: error("No resource for $url")
            return resource.copyOfRange(range.offset.toInt(), (range.offset + range.length).toInt())
        }

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

    /** Serves manifests that change with the clock: the latest entry at or before the current time is the one read. */
    private class ScheduledManifestSource(
        private val clock: FakeClock,
        private val schedule: List<Pair<Long, String>>,
        private val manifestUrl: String,
        private val segments: Map<String, ByteArray>,
        private val publishedAtSeconds: Map<String, Long> = emptyMap(),
        /** Manifest reads, counted from 1, that the origin does not answer. */
        private val unansweredReads: Set<Int> = emptySet(),
    ) : DashCaptureSource {
        val copied = ArrayList<String>()
        var manifestReads = 0

        override fun loadRange(url: String, range: HlsByteRange): ByteArray = error("no index in this fixture")

        override fun loadManifest(url: String): LoadedDashManifest {
            manifestReads++
            if (manifestReads in unansweredReads) throw IllegalStateException("manifest unavailable")
            return LoadedDashManifest(manifestUrl, schedule.last { it.first <= clock.now() }.second)
        }

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
            if (publishedAt != null && clock.now() < publishedAt) throw DashSegmentNotPublishedException()
            val bytes = segments[url] ?: error("No fake segment for $url")
            output.write(bytes)
            onProgress(bytes.size.toLong())
            return StreamRecorder.Result(bytes.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    /** A dynamic manifest that lists segments [first] to [last] of 3-second media in a SegmentList. */
    private fun listingManifest(first: Int, last: Int, representation: String = "v1"): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="$liveStart" minimumUpdatePeriod="PT2S">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <Representation id="$representation" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120">
        <SegmentList timescale="1000" duration="3000" startNumber="$first">
          <Initialization sourceURL="init-v1.m4s"/>
${(first..last).joinToString("\n") { "          <SegmentURL media=\"chunk-v1-%05d.m4s\"/>".format(it) }}
        </SegmentList>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""

    @Test fun liveSegmentListStartsAtTheLiveEdgeAndFollowsEachNewlyListedSegment() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock,
            listOf(1_000L to listingManifest(1, 3), 1_004L to listingManifest(2, 5), 1_008L to listingManifest(4, 7)),
            manifestUrl, liveSegments,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_012, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5, 6, 7), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl) + (3..7).map { chunk(it) }, source.copied)
    }

    @Test fun liveSegmentListStopsWhenTheListingMovesPastTheNextSegment() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock, listOf(1_000L to listingManifest(1, 3), 1_002L to listingManifest(6, 9)), manifestUrl, liveSegments,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("moved past"))
        assertEquals(listOf(9, 9, 3), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, chunk(3)), source.copied, "segments 4 and 5 were never listed in time")
    }

    @Test fun liveSegmentListStopsWhenTheListingStopsAdvancing() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(clock, listOf(1_000L to listingManifest(1, 3)), manifestUrl, liveSegments)
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("stopped advancing"))
        assertEquals(listOf(9, 9, 3), out.toByteArray().map { it.toInt() })
    }

    @Test fun aListedLiveSegmentNeverPublishedStopsAfterTheGraceWithTheBytesKept() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock, listOf(1_000L to listingManifest(2, 4)), manifestUrl, liveSegments,
            publishedAtSeconds = mapOf(chunk(4) to Long.MAX_VALUE),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH live segment was not published in time", result.detail)
        assertEquals(listOf(9, 9), out.toByteArray().map { it.toInt() })
    }

    @Test fun aChangedRepresentationStopsTheLiveListing() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock,
            listOf(1_000L to listingManifest(1, 3, representation = "v1"), 1_002L to listingManifest(2, 4, representation = "v2")),
            manifestUrl, liveSegments,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().contains("changed"))
        assertEquals(listOf(9, 9, 3), out.toByteArray().map { it.toInt() })
    }

    @Test fun liveSegmentListSurvivesOneUnansweredManifestRefresh() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock,
            listOf(1_000L to listingManifest(1, 3), 1_004L to listingManifest(2, 5)),
            manifestUrl, liveSegments,
            unansweredReads = setOf(2),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_008, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl) + (3..5).map { chunk(it) }, source.copied)
    }

    @Test fun liveSegmentListStopsWhenTheManifestStaysUnanswered() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock, listOf(1_000L to listingManifest(1, 3)), manifestUrl, liveSegments,
            unansweredReads = (2..100).toSet(),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH live manifest could not be read", result.detail)
        assertEquals(listOf(9, 9, 3), out.toByteArray().map { it.toInt() })
    }

    @Test fun cancellationDuringALiveSegmentListStopsWithoutFetchingMore() {
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock, listOf(1_000L to listingManifest(1, 3), 1_002L to listingManifest(2, 4)), manifestUrl, liveSegments,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, cancelled = { clock.now() >= 1_002 }, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.CANCELLED, result.stopReason)
        assertEquals(listOf(9, 9, 3), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, chunk(3)), source.copied, "the listing read at 1002 is not fetched")
    }

    /** Irregular segments addressed by start time. Each start serves one byte, in the order the starts are listed. */
    private fun irregularUrl(start: Long): String = "https://example.test/dash/chunk-$start.m4s"

    private val irregularSegments: Map<String, ByteArray> = mapOf(initUrl to byteArrayOf(9, 9)) +
        listOf(0L, 2_000L, 5_000L, 8_000L, 10_000L, 13_000L).mapIndexed { index, start ->
            irregularUrl(start) to byteArrayOf((index + 1).toByte())
        }

    /** A dynamic manifest whose SegmentTimeline is [timeline], addressed by start time. */
    private fun liveTimeline(timeline: String): String = mpd(type = "dynamic", availabilityStart = liveStart, body = timelineSet(timeline))

    @Test fun liveIrregularTimelineFollowsEachSegmentByItsStartTime() {
        // Each window lists the segments the recorder needs. A segment is fetched once its end time has passed, so the
        // recorder waits for segment 1 (complete at 1002) before fetching it, and so on through segment 4 (complete at 1010).
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock,
            listOf(
                1_000L to liveTimeline("""<S t="0" d="2000"/><S d="3000"/><S d="3000"/>"""),
                1_002L to liveTimeline("""<S t="0" d="2000"/><S d="3000"/><S d="3000"/><S d="2000"/>"""),
                1_004L to liveTimeline("""<S t="2000" d="3000"/><S d="3000"/><S d="2000"/>"""),
                1_006L to liveTimeline("""<S t="2000" d="3000"/><S d="3000"/><S d="2000"/><S d="3000"/>"""),
                1_008L to liveTimeline("""<S t="5000" d="3000"/><S d="2000"/><S d="3000"/>"""),
                1_010L to liveTimeline("""<S t="8000" d="2000"/><S d="3000"/><S d="2000"/>"""),
            ),
            manifestUrl, irregularSegments,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_012, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 1, 2, 3, 4), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, irregularUrl(0), irregularUrl(2_000), irregularUrl(5_000), irregularUrl(8_000)), source.copied)
    }

    @Test fun aLiveTimelineThatNoLongerContinuesTheRecordedSegmentsStopsWithTheBytesKept() {
        // After segment 1 is fetched, the next listing re-partitions the timeline: a segment at 1000 runs past 2000, so no
        // listed segment starts where the recorder expects one.
        val clock = FakeClock(1_000)
        val source = ScheduledManifestSource(
            clock,
            listOf(
                1_000L to liveTimeline("""<S t="0" d="2000"/><S d="3000"/><S d="3000"/>"""),
                1_004L to liveTimeline("""<S t="1000" d="2000"/><S d="2000"/>"""),
            ),
            manifestUrl, irregularSegments,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_100, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH live timeline does not continue the recorded segments", result.detail)
        assertEquals(listOf(9, 9, 1), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, irregularUrl(0)), source.copied)
    }

    @Test fun staticIrregularTimelineWritesEverySegmentInOrderAddressedByTime() {
        val byTime = mapOf(
            initUrl to byteArrayOf(9, 9),
            irregularUrl(0) to byteArrayOf(1),
            irregularUrl(3_000) to byteArrayOf(2),
            irregularUrl(5_000) to byteArrayOf(3),
            irregularUrl(9_000) to byteArrayOf(4),
        )
        val source = FakeDashSource(mpd(body = timelineSet("""<S t="0" d="3000"/><S d="2000"/><S d="4000" r="1"/>""")), byTime, manifestUrl)
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(listOf(9, 9, 1, 2, 3, 4), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, irregularUrl(0), irregularUrl(3_000), irregularUrl(5_000), irregularUrl(9_000)), source.copied)
    }

    /**
     * An irregular prefix [0, 2000) followed by an open repeat of 3000-unit segments from 2000, addressed by start time. The
     * prefix completes at 1002, and each tail segment completes 3 seconds after the one before it: at 1005, 1008, 1011 and so on.
     */
    private val openTailStarts = listOf(0L) + (2_000L..35_000L step 3_000L)
    private val openTailSegments: Map<String, ByteArray> = mapOf(initUrl to byteArrayOf(9, 9)) +
        openTailStarts.mapIndexed { index, start -> irregularUrl(start) to byteArrayOf((index + 1).toByte()) }

    /** A segment addressed by number, for the open tail of a timeline that uses $Number$. */
    private fun numberedUrl(number: Int): String = "https://example.test/dash/chunk-%05d.m4s".format(number)

    @Test fun aLiveTimelineWithAnIrregularPrefixFollowsItsOpenRepeatOnTheClock() {
        // The clock starts at 1003, after the prefix completes. The recording stops at 1012, after [8000, 11000) is fetched.
        val clock = FakeClock(1_003)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart, body = timelineSet("""<S t="0" d="2000"/><S d="3000" r="-1"/>""")),
            openTailSegments, manifestUrl, clock = clock,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_012, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 1, 2, 3, 4), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, irregularUrl(0), irregularUrl(2_000), irregularUrl(5_000), irregularUrl(8_000)), source.copied)
    }

    @Test fun aLiveIrregularOpenTailKeepsRecordingAcrossManifestRefreshesWithoutStalling() {
        // The listing never changes, so each refresh adds no entry. The open tail advances with the clock instead, so the
        // recording runs to its stop at 1040 and fetches each segment once it has completed.
        val clock = FakeClock(1_003)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart, body = timelineSet("""<S t="0" d="2000"/><S d="3000" r="-1"/>""")),
            openTailSegments, manifestUrl, clock = clock,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_040, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(initUrl, irregularUrl(0)) + (2_000L..35_000L step 3_000L).map { irregularUrl(it) }, source.copied)
        assertEquals(15, out.size())
    }

    @Test fun aLiveIrregularOpenTailStartsAtItsNewestCompleteSegment() {
        // At 1010 the segments ending at 2000, 5000 and 8000 are complete (at 1002, 1005 and 1008), and the one ending at 11000
        // is not (1011). The recording starts at [5000, 8000), the newest complete one, and stops at 1020.
        val clock = FakeClock(1_010)
        val source = FakeDashSource(
            mpd(type = "dynamic", availabilityStart = liveStart, body = timelineSet("""<S t="0" d="2000"/><S d="3000" r="-1"/>""")),
            openTailSegments, manifestUrl, clock = clock,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_020, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 3, 4, 5, 6), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, irregularUrl(5_000), irregularUrl(8_000), irregularUrl(11_000), irregularUrl(14_000)), source.copied)
    }

    @Test fun anIrregularOpenTailNumbersItsSegmentsAfterTheListedOnes() {
        // With $Number$ addressing the listed prefix is number 1, and each segment of the open tail takes the next number.
        val numbered = mapOf(initUrl to byteArrayOf(9, 9)) + (1..4).associate { number -> numberedUrl(number) to byteArrayOf(number.toByte()) }
        val clock = FakeClock(1_003)
        val source = FakeDashSource(
            mpd(
                type = "dynamic",
                availabilityStart = liveStart,
                body = timelineSet("""<S t="0" d="2000"/><S d="3000" r="-1"/>""", media = "chunk-@Number%05d@.m4s"),
            ),
            numbered, manifestUrl, clock = clock,
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out, end = 1_012, clock = clock)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(9, 9, 1, 2, 3, 4), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl) + (1..4).map { numberedUrl(it) }, source.copied)
    }

    /** A video set addressed by SegmentBase, with the byte ranges of its index and its initialization. */
    private fun segmentBaseSet(indexRange: String, initRange: String): String = """    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.64000a" width="160" height="120">
        <BaseURL>single.mp4</BaseURL>
        <SegmentBase indexRange="$indexRange"><Initialization range="$initRange"/></SegmentBase>
      </Representation>
    </AdaptationSet>"""

    @Test fun staticSegmentBaseReadsItsIndexThenCopiesTheInitializationAndEachFragment() {
        // One file: an initialization of 10 bytes, a sidx index of 44 bytes, then fragments of 12 and 8 bytes.
        val initialization = ByteArray(10) { (0x10 + it).toByte() }
        val index = SidxBox.build(references = listOf(12L to 2_000L, 8L to 2_000L))
        val fragments = ByteArray(12) { 0x41.toByte() } + ByteArray(8) { 0x51.toByte() }
        val file = initialization + index + fragments
        val indexEnd = 10 + index.size - 1
        val source = RangeDashSource(
            mpd(body = segmentBaseSet(indexRange = "10-$indexEnd", initRange = "0-9")),
            manifestUrl, mapOf(singleFileUrl to file),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals((initialization + fragments).toList(), out.toByteArray().toList())
        assertEquals(30L, result.bytesWritten)
        assertEquals<List<Pair<String, HlsByteRange?>>>(
            listOf(
                singleFileUrl to HlsByteRange(length = 10, offset = 0),
                singleFileUrl to HlsByteRange(length = 12, offset = 10L + index.size),
                singleFileUrl to HlsByteRange(length = 8, offset = 10L + index.size + 12),
            ),
            source.requests,
        )
    }

    @Test fun anUnavailableSegmentBaseIndexStopsBeforeAnyByteIsWritten() {
        val source = RangeDashSource(mpd(body = segmentBaseSet(indexRange = "10-53", initRange = "0-9")), manifestUrl, emptyMap())
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH segment index is not available", result.detail)
        assertEquals(0, out.size())
        assertEquals(emptyList<Pair<String, HlsByteRange?>>(), source.requests)
    }

    @Test fun aSegmentBaseIndexThatLeavesBytesOfTheFileUncoveredIsRefusedBeforeAnyByteIsWritten() {
        // One file: an initialization, a sidx index, two fragments, then four bytes that no reference names.
        val initialization = ByteArray(10) { (0x10 + it).toByte() }
        val index = SidxBox.build(references = listOf(12L to 2_000L, 8L to 2_000L))
        val fragments = ByteArray(12) { 0x41.toByte() } + ByteArray(8) { 0x51.toByte() }
        val file = initialization + index + fragments + ByteArray(4) { 0x7A.toByte() }
        val source = RangeDashSource(
            mpd(body = segmentBaseSet(indexRange = "10-${10 + index.size - 1}", initRange = "0-9")),
            manifestUrl,
            mapOf(singleFileUrl to file),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH SegmentBase index does not cover the file", result.detail)
        assertEquals(0, out.size())
        assertEquals(emptyList<Pair<String, HlsByteRange?>>(), source.requests)
    }

    @Test fun aSegmentBaseIndexThatNamesBytesPastTheFileIsRefusedBeforeAnyByteIsWritten() {
        // The index names fragments of 12 and 8 bytes, but the file ends 5 bytes short of the second one.
        val initialization = ByteArray(10) { (0x10 + it).toByte() }
        val index = SidxBox.build(references = listOf(12L to 2_000L, 8L to 2_000L))
        val fragments = ByteArray(12) { 0x41.toByte() } + ByteArray(3) { 0x51.toByte() }
        val file = initialization + index + fragments
        val source = RangeDashSource(
            mpd(body = segmentBaseSet(indexRange = "10-${10 + index.size - 1}", initRange = "0-9")),
            manifestUrl,
            mapOf(singleFileUrl to file),
        )
        val out = ByteArrayOutputStream()
        val result = record(source, out)
        assertEquals(DashCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals("DASH SegmentBase index names bytes past the end of the file", result.detail)
        assertEquals(0, out.size())
        assertEquals(emptyList<Pair<String, HlsByteRange?>>(), source.requests)
    }
}

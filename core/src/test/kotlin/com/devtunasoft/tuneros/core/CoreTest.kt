package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class CoreTest {
    @Test fun playlistMetadataAndHeaders() {
        val text = """#EXTM3U
#EXTINF:-1 tvg-id="news.us" tvg-logo="https://example.com/logo.png" group-title="News",News HD
#EXTVLCOPT:http-user-agent=TestAgent
https://example.com/live.m3u8
"""
        val channels = PlaylistParser.parse(text.reader())
        assertEquals(1, channels.size)
        assertEquals("news.us", channels.single().guideId)
        assertEquals("News", channels.single().group)
        assertEquals("TestAgent", channels.single().userAgent)
    }
    @Test fun unsupportedAndUnpairedUrlsAreRejected() {
        assertEquals(0, PlaylistParser.parse("#EXTINF:-1,Bad\nfile:///secret\n".reader()).size)
        assertEquals(0, PlaylistParser.parse("https://example.com/no-metadata".reader()).size)
    }
    @Test fun boundChannels() {
        val lines = "#EXTINF:-1,One\nhttps://example.com/one\n#EXTINF:-1,Two\nhttps://example.com/two\n"
        val report = PlaylistParser.parseReport(lines.reader(), 1)
        assertEquals(1, report.channels.size)
        assertTrue(report.warnings.single().contains("1 channels omitted"))
    }
    @Test fun xmlGuideParsing() {
        val xml = """<tv><channel id="news.us"><display-name>News</display-name></channel><programme channel="news.us" start="20261108100000 -0600" stop="20261108110000 -0600"><title>Morning News</title></programme></tv>"""
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals("News", guide.channelNames["news.us"])
        assertEquals("Morning News", guide.programs.single().title)
        assertEquals(guide.programs.single(), guide.now("news.us", guide.programs.single().startEpochSeconds + 1))
    }
    @Test fun windowRetainsOverlapsButExcludesOutOfRangeProgrammes() {
        val xml = """<tv>
            <programme channel="c" start="20261108080000 +0000" stop="20261108090000 +0000"><title>Before</title></programme>
            <programme channel="c" start="20261108093000 +0000" stop="20261108103000 +0000"><title>Crosses start</title></programme>
            <programme channel="c" start="20261108100000 +0000" stop="20261108110000 +0000"><title>Inside</title></programme>
            <programme channel="c" start="20261108113000 +0000" stop="20261108123000 +0000"><title>Crosses end</title></programme>
            <programme channel="c" start="20261108120000 +0000" stop="20261108130000 +0000"><title>After</title></programme>
        </tv>"""
        val start = LocalDateTime.of(2026, 11, 8, 10, 0).toEpochSecond(ZoneOffset.UTC)
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()),
            window = GuideWindow(start, start + 7200))
        assertEquals(listOf("Crosses start", "Inside", "Crosses end"), guide.programs.map { it.title })
        assertEquals("Inside", guide.now("c", start + 1800)?.title)
        assertTrue(guide.warnings.isEmpty())
    }

    @Test fun windowExcludedProgrammesDoNotConsumeProgrammeCap() {
        val xml = """<tv>
            <programme channel="c" start="20261108080000 +0000" stop="20261108090000 +0000"><title>Outside</title></programme>
            <programme channel="c" start="20261108100000 +0000" stop="20261108110000 +0000"><title>Inside</title></programme>
        </tv>"""
        val start = LocalDateTime.of(2026, 11, 8, 10, 0).toEpochSecond(ZoneOffset.UTC)
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()),
            maxPrograms = 1, window = GuideWindow(start, start + 3600))
        assertEquals(listOf("Inside"), guide.programs.map { it.title })
        assertTrue(guide.warnings.isEmpty())
    }

    @Test fun descriptionsCanBeDroppedWithoutChangingDefault() {
        val xml = """<tv><programme channel="c" start="20261108100000 +0000" stop="20261108110000 +0000">
            <title>News</title><desc>Detailed synopsis</desc></programme></tv>"""
        val defaultGuide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
        val leanGuide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()), includeDescriptions = false)
        assertEquals("Detailed synopsis", defaultGuide.programs.single().description)
        assertEquals(null, leanGuide.programs.single().description)
    }

    @Test fun invalidWindowBoundariesAreRejected() {
        assertFailsWith<IllegalArgumentException> { GuideWindow(10, 10) }
        assertFailsWith<IllegalArgumentException> { GuideWindow(10, 9) }
    }

    @Test fun futureEpgCountsAsScheduleEvenWithoutCurrentProgram() {
        val future = GuideProgram("news.us", "Tomorrow", 1_000_000L, 1_003_600L)
        val guide = GuideSnapshot(listOf(future), mapOf("news.us" to "News"))
        guide.prepareIndex()
        assertEquals(true, guide.hasSchedule("news.us"))
        assertEquals(false, guide.hasSchedule("missing"))
        assertEquals(null, guide.now("news.us", 999_999L))
        assertEquals(future, guide.now("news.us", 1_000_001L))
    }
    @Test fun xxeIsBlocked() {
        val xml = "<!DOCTYPE tv [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><tv><channel id=\"a\"><display-name>&xxe;</display-name></channel></tv>"
        assertFailsWith<Exception> { GuideParser.parse(ByteArrayInputStream(xml.toByteArray())) }
    }
    @Test fun invalidProgramIsIgnored() {
        val xml = """<tv><programme channel="x" start="bad" stop="20261108110000 -0600"><title>Ignored</title></programme></tv>"""
        assertEquals(0, GuideParser.parse(ByteArrayInputStream(xml.toByteArray())).programs.size)
    }

    @Test fun duplicateGuideIdFromDifferentStreamsDoesNotCollide() {
        val feed = "#EXTINF:-1 tvg-id=\"shared\",First\nhttps://example.org/one\n" +
            "#EXTINF:-1 tvg-id=\"shared\",Second\nhttps://example.org/two\n"
        val channels = PlaylistParser.parse(feed.reader())
        assertEquals(2, channels.size)
        assertNotEquals(channels[0].id, channels[1].id)
        val otherSource = PlaylistParser.parseReport(feed.reader(), sourceId = "another")
        assertNotEquals(channels[0].id, otherSource.channels[0].id)
    }
    @Test fun hugeLineIsRejectedByBoundedReader() {
        val input = "#EXTM3U\n" + "X".repeat(PlaylistParser.MAX_LINE_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> { PlaylistParser.parse(input.reader()) }
    }
    @Test fun unsortedOverlappingGuideFindsLatestActiveEntry() {
        val long = GuideProgram("c", "Long", 0, 1000)
        val short = GuideProgram("c", "Short", 50, 60)
        val later = GuideProgram("c", "Later", 200, 300)
        val guide = GuideSnapshot(listOf(later, short, long), emptyMap())
        guide.prepareIndex()
        assertEquals(short, guide.now("c", 55))
        assertEquals(long, guide.now("c", 75))
        assertEquals(later, guide.now("c", 250))
        assertEquals(null, guide.now("c", 1001))
        assertEquals(long, guide.currentProgramsAt(75)["c"])
    }
    @Test fun timezoneMissingDefaultsToUtcAndWarns() {
        val xml = """<tv><programme channel="c" start="20261108100000" stop="20261108110000"><title>UTC</title></programme></tv>"""
        val report = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
        val expected = LocalDateTime.of(2026, 11, 8, 10, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals(expected, report.programs.single().startEpochSeconds)
        assertTrue(report.warnings.any { it.contains("assumed UTC") })
    }
    @Test fun xmltvProgrammeCapWarnsWithoutDiscardingGuide() {
        val xml = """<tv><channel id="a"><display-name>A</display-name></channel><channel id="b"><display-name>B</display-name></channel><programme channel="a" start="20261108100000 +0000" stop="20261108110000 +0000"><title>A</title></programme><programme channel="b" start="20261108110000 +0000" stop="20261108120000 +0000"><title>B</title></programme></tv>"""
        val report = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()), maxPrograms = 1, maxChannels = 1)
        assertEquals(1, report.programs.size)
        assertEquals(1, report.channelNames.size)
        assertTrue(report.warnings.any { it.contains("1 programmes omitted") })
        assertTrue(report.warnings.any { it.contains("1 channel names omitted") })
    }
    private class StubConnection(url: URL, private val status: Int = 200,
                                 private val body: ByteArray = byteArrayOf(),
                                 private val location: String? = null,
                                 private val encoding: String? = null) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getContentEncoding(): String? = encoding
    }
    @Test fun gzipWithoutHeadersIsDetectedByMagicBytes() {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("guide".toByteArray()) }
        }.toByteArray()
        val loader = SourceDownloader { StubConnection(it, body = compressed) }
        assertEquals("guide", loader.open("https://example.org/guide", 100).bufferedReader().use { it.readText() })
    }
    @Test fun decompressedByteLimitIsEnforced() {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("A".repeat(100).toByteArray()) }
        }.toByteArray()
        val loader = SourceDownloader { StubConnection(it, body = compressed) }
        assertFailsWith<IllegalArgumentException> {
            loader.open("https://example.org/guide", 10).use { it.readBytes() }
        }
    }
    @Test fun skippingDecompressedDataStillEnforcesLimit() {
        val loader = SourceDownloader { StubConnection(it, body = ByteArray(32) { 65 }) }
        loader.open("https://example.org/feed", 10).use { source ->
            assertFailsWith<IllegalArgumentException> { source.skip(20) }
        }
    }

    @Test fun skipReturnsActualBytesAndSubsequentReadsCountTowardsLimit() {
        val loader = SourceDownloader { StubConnection(it, body = "abcdefghij".toByteArray()) }
        loader.open("https://example.org/feed", 10).use { source ->
            assertEquals(4L, source.skip(4))
            assertEquals('e'.code, source.read())
            assertEquals(5L, source.skip(30))
            assertEquals(-1, source.read())
        }
    }

    @Test fun httpsRedirectCannotDowngrade() {
        val loader = SourceDownloader { StubConnection(it, status = 302, location = "http://example.org/feed") }
        assertFailsWith<IllegalArgumentException> { loader.open("https://example.org/feed", 100) }
    }
    @Test fun httpRedirectCanUpgradeToHttps() {
        val loader = SourceDownloader {
            if (it.protocol == "http") StubConnection(it, status = 301, location = "https://example.org/feed")
            else StubConnection(it, body = "ok".toByteArray())
        }
        assertEquals("ok", loader.open("http://example.org/feed", 100).bufferedReader().use { it.readText() })
    }
    @Test fun malformedSourceUrlsAreRejected() {
        assertFailsWith<IllegalArgumentException> { SourceDownloader.checkedUrl("file:///etc/passwd") }
        assertFailsWith<IllegalArgumentException> { SourceDownloader.checkedUrl("https://") }
    }
    @Test fun multiviewRespectsBothLimits() {
        assertEquals(2, MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(4, 2)))
        assertEquals(0, MultiviewPolicy.allowedTiles(MultiviewPolicy.Capacity(0, 4)))
        assertEquals(1, MultiviewPolicy.chooseAudioTile(1, 3))
        assertFailsWith<IllegalArgumentException> { MultiviewPolicy.chooseAudioTile(3, 3) }
    }
    @Test fun timelineCreatesContiguousCableGuideRow() {
        val programs = listOf(
            GuideProgram("c", "News", 100, 200),
            GuideProgram("c", "Weather", 250, 400),
        )
        val row = TimelineLayout.row(programs, 50, 350)
        assertEquals(listOf(50L, 100L, 200L, 250L), row.map { it.startEpochSeconds })
        assertEquals(listOf(100L, 200L, 250L, 350L), row.map { it.endEpochSeconds })
        assertEquals(listOf(true, false, true, false), row.map { it.isGap })
        assertEquals(listOf(null, "News", null, "Weather"), row.map { it.program?.title })
    }

    @Test fun timelineClipsOverlapsAndWindowEdges() {
        val programs = listOf(
            GuideProgram("c", "Early", 0, 120),
            GuideProgram("c", "Overlap", 100, 180),
            GuideProgram("c", "Late", 170, 300),
        )
        val row = TimelineLayout.row(programs, 50, 250)
        assertEquals(listOf("Early", "Overlap", "Late"), row.mapNotNull { it.program?.title })
        assertEquals(50L, row.first().startEpochSeconds)
        assertEquals(250L, row.last().endEpochSeconds)
        assertTrue(row.zipWithNext().all { (left, right) -> left.endEpochSeconds == right.startEpochSeconds })
    }

    @Test fun timelineEmptyWindowBecomesOneGapAndWidthsAreProportional() {
        val gap = TimelineLayout.row(emptyList(), 0, 3600).single()
        assertTrue(gap.isGap)
        assertEquals(240, TimelineLayout.widthUnits(gap))
        assertEquals(120, TimelineLayout.widthUnits(TimelineEntry(0, 1800)))
        assertFailsWith<IllegalArgumentException> { TimelineLayout.row(emptyList(), 10, 10) }
    }

    @Test fun indexedGuideWindowReturnsOnlyIntersectingPrograms() {
        val guide = GuideSnapshot(
            listOf(
                GuideProgram("c", "Before", 0, 100),
                GuideProgram("c", "Crosses", 90, 150),
                GuideProgram("c", "Inside", 120, 180),
                GuideProgram("c", "After", 200, 300),
            ),
            emptyMap(),
        )
        guide.prepareIndex()
        assertEquals(listOf("Crosses", "Inside"), guide.programsBetween("c", 100, 200).map { it.title })
        assertTrue(guide.programsBetween("missing", 100, 200).isEmpty())
        assertFailsWith<IllegalArgumentException> { guide.programsBetween("c", 100, 100) }
    }

    @Test fun recordingRequestUsesPaddingWithoutMediaState() {
        val channel = Channel("ch", "News", "https://example.org/live.ts")
        val program = GuideProgram("guide", "Evening News", 1_000, 2_000)
        val request = RecordingPolicy.fromProgram(channel, program, "rec-1", 60, 120)
        assertEquals(940L, request.captureStartEpochSeconds)
        assertEquals(2_120L, request.captureEndEpochSeconds)
        assertEquals("https://example.org/live.ts", request.streamUrl)
    }

    @Test fun recordingConflictUsesPaddedCaptureWindow() {
        val first = RecordingRequest("a", "c1", "A", "https://example.org/a", 1_000, 2_000, endPaddingSeconds = 60)
        val overlapsPadding = RecordingRequest("b", "c2", "B", "https://example.org/b", 2_030, 3_000)
        val afterPadding = RecordingRequest("c", "c3", "C", "https://example.org/c", 2_060, 3_000)
        assertTrue(RecordingPolicy.conflicts(first, overlapsPadding))
        assertEquals(false, RecordingPolicy.conflicts(first, afterPadding))
        assertFailsWith<IllegalArgumentException> {
            RecordingRequest("bad", "c", "Bad", "file:///tmp/a", 1, 2)
        }
    }

    @Test fun recordingProgressRejectsNegativeByteCounts() {
        assertFailsWith<IllegalArgumentException> {
            RecordingProgress("rec", RecordingState.RECORDING, bytesWritten = -1)
        }
        assertEquals(64 * 1024, RecordingPolicy.COPY_BUFFER_BYTES)
    }

    @Test fun streamRecorderCopiesWithoutGrowingBufferWithRecordingLength() {
        val payload = ByteArray(RecordingPolicy.COPY_BUFFER_BYTES * 5 + 17) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        var lastProgress = 0L
        val result = StreamRecorder(minFreeBytes = 0).copy(
            ByteArrayInputStream(payload), output, freeBytes = { Long.MAX_VALUE },
            onProgress = { lastProgress = it },
        )
        assertEquals(StreamRecorder.StopReason.END_OF_STREAM, result.stopReason)
        assertEquals(payload.size.toLong(), result.bytesWritten)
        assertEquals(payload.size.toLong(), lastProgress)
        assertTrue(payload.contentEquals(output.toByteArray()))
    }

    @Test fun streamRecorderCancelsAndPreservesPartialOutput() {
        val payload = ByteArray(200_000) { 7 }
        val output = ByteArrayOutputStream()
        var copied = 0L
        val result = StreamRecorder(bufferBytes = 4 * 1024, minFreeBytes = 0).copy(
            ByteArrayInputStream(payload), output,
            freeBytes = { Long.MAX_VALUE },
            cancelled = { copied >= 12 * 1024 },
            onProgress = { copied = it },
        )
        assertEquals(StreamRecorder.StopReason.CANCELLED, result.stopReason)
        assertEquals(copied, result.bytesWritten)
        assertEquals(copied.toInt(), output.size())
        assertTrue(result.bytesWritten in 12 * 1024L until payload.size.toLong())
    }

    @Test fun streamRecorderStopsBeforeWritingWhenStorageReserveIsTooLow() {
        val recorder = StreamRecorder(bufferBytes = 4 * 1024, minFreeBytes = 10_000)
        val output = ByteArrayOutputStream()
        assertFailsWith<IllegalArgumentException> {
            recorder.copy(ByteArrayInputStream(ByteArray(100)), output, freeBytes = { 9_999 })
        }
        assertEquals(0, output.size())
    }

    @Test fun streamRecorderStopsMidCaptureWhenStorageFallsBelowReserve() {
        val output = ByteArrayOutputStream()
        var freeChecks = 0
        val result = StreamRecorder(bufferBytes = 4 * 1024, minFreeBytes = 10_000).copy(
            ByteArrayInputStream(ByteArray(50_000) { 1 }), output,
            freeBytes = {
                freeChecks++
                if (freeChecks < 4) 20_000 else 9_000
            },
        )
        assertEquals(StreamRecorder.StopReason.LOW_STORAGE, result.stopReason)
        assertTrue(result.bytesWritten > 0)
        assertTrue(result.bytesWritten < 50_000)
        assertEquals(result.bytesWritten.toInt(), output.size())
    }

    @Test fun streamRecorderCanReuseCallerOwnedBuffer() {
        val provided = ByteArray(4 * 1024)
        var sawProvidedBuffer = false
        val input = object : InputStream() {
            private var emitted = false
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(buffer === provided)
                sawProvidedBuffer = true
                if (emitted) return -1
                emitted = true
                buffer[offset] = 42
                return 1
            }
        }
        val output = ByteArrayOutputStream()
        val result = StreamRecorder(bufferBytes = provided.size, minFreeBytes = 0).copy(
            input = input,
            output = output,
            freeBytes = { Long.MAX_VALUE },
            buffer = provided,
        )
        assertTrue(sawProvidedBuffer)
        assertEquals(1L, result.bytesWritten)
        assertEquals(42, output.toByteArray().single().toInt())
        assertFailsWith<IllegalArgumentException> {
            StreamRecorder(bufferBytes = 4 * 1024, minFreeBytes = 0).copy(
                input = ByteArrayInputStream(byteArrayOf(1)),
                output = ByteArrayOutputStream(),
                freeBytes = { Long.MAX_VALUE },
                buffer = ByteArray(1024),
            )
        }
    }

    @Test fun timelineFocusPolicySkipsGapsWithoutAllocatingSelectableLists() {
        val entries = listOf(
            TimelineEntry(0, 10),
            TimelineEntry(10, 20, GuideProgram("c", "One", 10, 20)),
            TimelineEntry(20, 30),
            TimelineEntry(30, 40, GuideProgram("c", "Two", 30, 40)),
        )
        assertEquals(1, TimelineFocusPolicy.preferredIndex(entries, null))
        assertEquals(3, TimelineFocusPolicy.preferredIndex(entries, 30))
        assertEquals(3, TimelineFocusPolicy.nextIndex(entries, 1, 1))
        assertEquals(1, TimelineFocusPolicy.nextIndex(entries, 3, -1))
        assertEquals(-1, TimelineFocusPolicy.nextIndex(entries, 3, 1))
        assertEquals(-1, TimelineFocusPolicy.preferredIndex(listOf(TimelineEntry(0, 10)), null))
        assertFailsWith<IllegalArgumentException> {
            TimelineFocusPolicy.nextIndex(entries, 1, 0)
        }
    }

    @Test fun denseTimelineIsNotTruncatedToLegacyEightCells() {
        val programs = (0 until 24).map { index ->
            val start = index * 450L
            GuideProgram("dense", "P$index", start, start + 450L)
        }
        val row = TimelineLayout.row(programs, 0, 24 * 450L)
        assertEquals(24, row.size)
        assertEquals((0 until 24).map { "P$it" }, row.mapNotNull { it.program?.title })
    }

    @Test fun dpadWindowBoundaryFocusTracksDirectionAndSkipsGaps() {
        val entries = listOf(
            TimelineEntry(0, 10),
            TimelineEntry(10, 20, GuideProgram("c", "Older", 10, 20)),
            TimelineEntry(20, 30),
            TimelineEntry(30, 40, GuideProgram("c", "Middle", 30, 40)),
            TimelineEntry(40, 50, GuideProgram("c", "Newer", 40, 50)),
        )
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, 45, -1))
        assertEquals(3, TimelineFocusPolicy.shiftedIndex(entries, 40, -1))
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, 30, 1))
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(entries, 10, -1))
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, 50, 1))
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, 50, -1))
        assertEquals(4, TimelineFocusPolicy.shiftedIndex(entries, null, -1))
        assertEquals(1, TimelineFocusPolicy.shiftedIndex(entries, null, 1))
        assertEquals(-1, TimelineFocusPolicy.shiftedIndex(listOf(TimelineEntry(0, 10)), 10, -1))
        assertFailsWith<IllegalArgumentException> {
            TimelineFocusPolicy.shiftedIndex(entries, null, 0)
        }
    }


    @Test fun shiftedTimelineFocusProperty() {
        val random = kotlin.random.Random(16422)
        repeat(500) {
            val entries = (0 until random.nextInt(0, 40)).map { index ->
                val start = index * 10L
                TimelineEntry(
                    start, start + 10L,
                    if (random.nextBoolean()) GuideProgram("c", "Show$index", start, start + 10L)
                    else null,
                )
            }
            val anchor = if (random.nextInt(7) == 0) null
                else random.nextInt(-3, entries.size + 4) * 10L
            val available = entries.mapIndexedNotNull { index, item ->
                item.program?.let { index to it.startEpochSeconds }
            }
            for (direction in listOf(-1, 1)) {
                val eligible = if (anchor == null) emptyList() else available.filter {
                    if (direction < 0) it.second < anchor else it.second > anchor
                }
                val expected = when {
                    available.isEmpty() -> -1
                    eligible.isNotEmpty() ->
                        if (direction < 0) eligible.last().first else eligible.first().first
                    anchor != null && available.any { it.second == anchor } ->
                        available.first { it.second == anchor }.first
                    anchor != null && direction > 0 && available.last().second <= anchor ->
                        available.last().first
                    anchor != null && direction < 0 && available.first().second >= anchor ->
                        available.first().first
                    direction < 0 -> available.last().first
                    else -> available.first().first
                }
                assertEquals(
                    expected,
                    TimelineFocusPolicy.shiftedIndex(entries, anchor, direction),
                )
            }
        }
    }


}

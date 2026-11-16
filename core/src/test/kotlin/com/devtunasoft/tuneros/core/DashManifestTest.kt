package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashManifestTest {
    private val videoSet = """    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" duration="3000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="chunk-@RepresentationID@-@Number%05d@.m4s"/>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>"""

    /**
     * `@` stands for a dollar sign, so the fixtures read like manifests without Kotlin template escapes. A static
     * manifest carries a presentation duration; a dynamic one carries an availability start instead.
     */
    private fun mpd(
        type: String = "static",
        duration: String = "PT9S",
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

    /** A video set whose SegmentTemplate carries [timeline] in place of a fixed duration. */
    private fun timelineSet(timeline: String, media: String = "chunk-@Time@.m4s"): String = """    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="$media">
        <SegmentTimeline>$timeline</SegmentTimeline>
      </SegmentTemplate>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>"""

    private fun refusalOf(text: String): String {
        val outcome = DashManifest.plan(text)
        assertTrue(outcome is DashPlanOutcome.Unsupported, "expected a refusal, got $outcome")
        return (outcome as DashPlanOutcome.Unsupported).reason
    }

    private fun planned(text: String): DashPlan {
        val outcome = DashManifest.plan(text)
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        return (outcome as DashPlanOutcome.Planned).plan
    }

    /** The start time of segment [number] in timescale units, as the recorder computes it. */
    private fun timeOf(plan: DashPlan, number: Long): Long =
        plan.startTimeTicks + (number - plan.startNumber) * plan.segmentDurationTicks

    @Test fun planCarriesTheRepresentationAndItsTemplates() {
        val outcome = DashManifest.plan(mpd())
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        val plan = (outcome as DashPlanOutcome.Planned).plan
        assertEquals("v1", plan.representationId)
        assertEquals("init-\$RepresentationID\$.m4s", plan.initializationTemplate)
        assertEquals("chunk-\$RepresentationID\$-\$Number%05d\$.m4s", plan.mediaTemplate)
        assertEquals(3.0, plan.segmentDurationSeconds)
        assertEquals(3L, plan.segmentCount)
        assertEquals(DashTiming.Static(9.0), plan.timing)
        assertEquals(1L, plan.startNumber)
        assertNull(plan.baseUrl)
    }

    @Test fun liveManifestWithAnAvailabilityStartIsPlanned() {
        val outcome = DashManifest.plan(mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z"))
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        val plan = (outcome as DashPlanOutcome.Planned).plan
        assertEquals(DashTiming.Live(1_000.0), plan.timing)
        assertNull(plan.segmentCount)
    }

    @Test fun aLiveManifestWrittenByFfmpegPlansAgainstItsSegmentClock() {
        // Real output of ffmpeg 2-second live DASH. It declares availabilityTimeOffset, which the recorder does not
        // use: each segment is requested only after its full duration has elapsed.
        val text = DashManifestTest::class.java.getResource("/dash/ffmpeg-live.mpd")!!.readText()
        val plan = (DashManifest.plan(text) as DashPlanOutcome.Planned).plan
        val timing = plan.timing as DashTiming.Live
        assertEquals(1_794_833_535.972, timing.availabilityStartEpochSeconds, 0.001)
        assertEquals(2.0, plan.segmentDurationSeconds)
        assertEquals(1L, plan.startNumber)
        assertEquals("init-stream0.m4s", DashManifest.expand(plan.initializationTemplate!!, plan.representationId, plan.bandwidth, 1))
        assertEquals("chunk-stream0-00007.m4s", DashManifest.expand(plan.mediaTemplate, plan.representationId, plan.bandwidth, 7))
    }

    @Test fun aFfmpegStaticTimelineIsPlannedAsARegularTimeline() {
        // Real output of ffmpeg with -use_timeline 1: one entry of six segments, numbered addressing, no duration.
        val text = DashManifestTest::class.java.getResource("/dash/ffmpeg-static-timeline.mpd")!!.readText()
        val plan = planned(text)
        assertEquals(DashTiming.Static(12.0), plan.timing)
        assertEquals(6L, plan.segmentCount)
        assertEquals(2.0, plan.segmentDurationSeconds)
        assertEquals(0L, plan.startTimeTicks)
    }

    @Test fun aFfmpegLiveTimelineWindowIsPlannedAsATimedListing() {
        // Real output of ffmpeg live DASH with a SegmentTimeline: a finite window that a refresh extends. Each listed
        // segment is fetched once it is complete.
        val text = DashManifestTest::class.java.getResource("/dash/ffmpeg-live-timeline.mpd")!!.readText()
        val plan = planned(text)
        assertTrue(plan.timing is DashTiming.Live)
        assertTrue(plan.listedTimed)
        assertNull(plan.segmentCount)
        assertEquals(2.0, plan.segmentDurationSeconds)
        assertEquals(0L, plan.startTimeTicks)
    }

    @Test fun liveAvailabilityStartKeepsItsFractionalSecondAndOffset() {
        // 01:00:00.5 at +01:00 is 00:00:00.5 UTC, which is 0.5 seconds after the epoch.
        val outcome = DashManifest.plan(mpd(type = "dynamic", availabilityStart = "1970-01-01T01:00:00.5+01:00"))
        assertEquals(DashTiming.Live(0.5), (outcome as DashPlanOutcome.Planned).plan.timing)
    }

    @Test fun liveManifestNeedsAnAvailabilityStartWithAnOffset() {
        val reason = "Live DASH manifest needs a valid availabilityStartTime"
        assertEquals(reason, refusalOf(mpd(type = "dynamic")))
        assertEquals(reason, refusalOf(mpd(type = "dynamic", availabilityStart = "2026-11-16T10:00:00")))
        assertEquals(reason, refusalOf(mpd(type = "dynamic", availabilityStart = "yesterday")))
    }

    @Test fun separateAudioRenditionIsRefused() {
        val audio = """    <AdaptationSet contentType="audio" mimeType="audio/mp4">
      <SegmentTemplate timescale="1000" duration="3000" media="a-@Number@.m4s"/>
      <Representation id="a1" bandwidth="128000" codecs="mp4a.40.2"/>
    </AdaptationSet>"""
        assertTrue(refusalOf(mpd(body = videoSet + "\n" + audio)).contains("Separate audio"))
    }

    @Test fun protectionIsRefused() {
        val protected = videoSet.replace(
            "<Representation", "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\"/>\n      <Representation",
        )
        assertTrue(refusalOf(mpd(body = protected)).contains("ContentProtection"))
    }

    /** A video set addressed by SegmentList. [base] adds the representation's own BaseURL. */
    private fun listSet(
        body: String,
        base: String? = null,
        initialization: String = """<Initialization range="0-814"/>""",
    ): String = """    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.64000a" width="160" height="120">
        ${base?.let { "<BaseURL>$it</BaseURL>" }.orEmpty()}
        <SegmentList timescale="1000" duration="3000" startNumber="1">
          $initialization
          $body
        </SegmentList>
      </Representation>
    </AdaptationSet>"""

    @Test fun aDocumentTypeIsRejectedBeforeAnyParserSeesIt() {
        val text = "<!DOCTYPE MPD [ <!ENTITY x \"y\"> ]>\n" + mpd()
        assertTrue(refusalOf(text).contains("DTD"))
    }

    @Test fun notWellFormedXmlIsRefused() {
        assertEquals("DASH manifest is not well formed", refusalOf("<MPD>"))
    }

    @Test fun aTemplateWithAnUnsupportedTokenIsRefused() {
        // $Time$ needs a SegmentTimeline; this template has a fixed duration, so the token is refused.
        val timeTemplate = videoSet.replace("@Number%05d@", "@Time@")
        assertTrue(refusalOf(mpd(body = timeTemplate)).contains("token"))
    }

    @Test fun aRepresentationWithoutASegmentTemplateIsRefused() {
        val bare = """    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e"/>
    </AdaptationSet>"""
        assertTrue(refusalOf(mpd(body = bare)).contains("SegmentTemplate"))
    }

    @Test fun templateExpansionFormatsNumbersAndRejectsUnknownTokens() {
        assertEquals("chunk-00007.m4s", DashManifest.expand("chunk-\$Number%05d\$.m4s", "v1", 500000, 7))
        assertEquals("a-500000-\$", DashManifest.expand("a-\$Bandwidth\$-\$\$", "v1", 500000, 1))
        assertEquals("v1/1.m4s", DashManifest.expand("\$RepresentationID\$/\$Number\$.m4s", "v1", 500000, 1))
        assertNull(DashManifest.expand("\$Time\$", "v1", 500000, 1))
        assertNull(DashManifest.expand("\$Number%0d\$", "v1", 500000, 1))
        assertNull(DashManifest.expand("\$Number%010d\$", "v1", 500000, 1))
        assertNull(DashManifest.expand("unterminated\$", "v1", 500000, 1))
    }

    @Test fun timeTokenExpandsToTheSegmentStartWhenATimelineGivesIt() {
        assertEquals("chunk-6000.m4s", DashManifest.expand("chunk-\$Time\$.m4s", "v1", 500000, 3, 6000))
    }

    @Test fun regularStaticTimelineCountsItsSegmentsAndAddressesThemByTime() {
        val plan = planned(mpd(body = timelineSet("""<S t="0" d="3000" r="2"/>""")))
        assertEquals(DashTiming.Static(9.0), plan.timing)
        assertEquals(3L, plan.segmentCount)
        assertEquals("chunk-3000.m4s", DashManifest.expand(plan.mediaTemplate, plan.representationId, plan.bandwidth, 2, timeOf(plan, 2)))
    }

    @Test fun theFirstTimelineEntryFixesTheStartTime() {
        val plan = planned(mpd(body = timelineSet("""<S t="6000" d="3000" r="1"/>""")))
        assertEquals(6000L, plan.startTimeTicks)
        assertEquals(2L, plan.segmentCount)
        assertEquals("chunk-6000.m4s", DashManifest.expand(plan.mediaTemplate, plan.representationId, plan.bandwidth, 1, timeOf(plan, 1)))
    }

    @Test fun contiguousEntriesWithEqualDurationsAreOneRegularTimeline() {
        val plan = planned(mpd(body = timelineSet("""<S t="0" d="3000" r="1"/><S d="3000" r="1"/>""")))
        assertEquals(4L, plan.segmentCount)
    }

    @Test fun irregularStaticTimelineIsPlannedAsSegmentsAddressedByTime() {
        val plan = planned(mpd(body = timelineSet("""<S t="0" d="3000"/><S d="2000"/><S d="4000" r="1"/>""")))
        assertEquals(4L, plan.segmentCount)
        val listed = plan.listedSegments!!
        assertEquals(listOf(0L, 3_000L, 5_000L, 9_000L), listed.map { it.id })
        assertEquals(listOf(3_000L, 5_000L, 9_000L, 13_000L), listed.map { it.endId })
        assertEquals(listOf("chunk-0.m4s", "chunk-3000.m4s", "chunk-5000.m4s", "chunk-9000.m4s"), listed.map { it.media })
    }

    @Test fun irregularLiveTimelineIsPlannedAsAListingAddressedByTime() {
        val plan = planned(
            mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z", body = timelineSet("""<S t="600000" d="2000"/><S d="3000"/>""")),
        )
        assertTrue(plan.timing is DashTiming.Live)
        assertNull(plan.segmentCount)
        val listed = plan.listedSegments!!
        assertEquals(listOf(600_000L, 602_000L), listed.map { it.id })
        assertEquals(listOf(602_000L, 605_000L), listed.map { it.endId })
        assertEquals(listOf("chunk-600000.m4s", "chunk-602000.m4s"), listed.map { it.media })
    }

    @Test fun irregularLiveTimelineWithNumberAddressingFollowsTheWindowStartNumber() {
        // The window's startNumber names its first segment, and each following entry takes the next number.
        val text = mpd(
            type = "dynamic",
            availabilityStart = "1970-01-01T00:16:40Z",
            body = timelineSet("""<S t="0" d="2000"/><S d="3000"/>""", media = "chunk-@Number%05d@.m4s")
                .replace("startNumber=\"1\"", "startNumber=\"5\""),
        )
        assertEquals(listOf("chunk-00005.m4s", "chunk-00006.m4s"), planned(text).listedSegments!!.map { it.media })
    }

    @Test fun timedListingReadsThePresentationOffset() {
        val plan = planned(
            mpd(
                type = "dynamic",
                availabilityStart = "1970-01-01T00:16:40Z",
                body = timelineSet("""<S t="600000" d="2000"/><S d="3000"/>""")
                    .replace("startNumber=\"1\"", "startNumber=\"1\" presentationTimeOffset=\"600000\""),
            ),
        )
        assertTrue(plan.listedTimed)
        assertEquals(600_000L, plan.presentationTimeOffsetTicks)
    }

    @Test fun irregularLiveTimelineCannotRepeatToTheEnd() {
        val text = mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z", body = timelineSet("""<S t="0" d="2000"/><S d="3000" r="-1"/>"""))
        assertTrue(refusalOf(text).contains("repeat to the end"))
    }

    @Test fun irregularTimelineAboveTheSegmentBoundIsRefused() {
        assertTrue(refusalOf(mpd(body = timelineSet("""<S t="0" d="1000" r="5000"/><S d="2000" r="5000"/>"""))).contains("too many"))
    }

    @Test fun timelineWithAGapIsRefused() {
        assertTrue(refusalOf(mpd(body = timelineSet("""<S t="0" d="3000" r="1"/><S t="9000" d="3000"/>"""))).contains("contiguous"))
    }

    @Test fun staticTimelineRepeatingToTheEndIsRefused() {
        assertTrue(refusalOf(mpd(body = timelineSet("""<S t="0" d="3000" r="-1"/>"""))).contains("live"))
    }

    @Test fun timelineEntriesNeedADurationAndAValidRepeat() {
        assertTrue(refusalOf(mpd(body = timelineSet("""<S t="0" r="1"/>"""))).contains("duration"))
        assertTrue(refusalOf(mpd(body = timelineSet("""<S t="0" d="3000" r="x"/>"""))).contains("repeat"))
        assertTrue(refusalOf(mpd(body = timelineSet("""<S t="0" d="3000" r="-2"/>"""))).contains("repeat"))
    }

    @Test fun timelineWithTooManyEntriesIsRefused() {
        val entries = (1..1001).joinToString("") { """<S d="1000"/>""" }
        assertTrue(refusalOf(mpd(body = timelineSet(entries))).contains("too many entries"))
    }

    @Test fun openLiveTimelineIsPlannedOnTheClock() {
        val plan = planned(mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z", body = timelineSet("""<S t="0" d="3000" r="-1"/>""")))
        assertEquals(DashTiming.Live(1_000.0), plan.timing)
        assertNull(plan.segmentCount)
    }

    @Test fun finiteLiveTimelineWindowIsPlannedAsATimedListingWithNumberAddressing() {
        // A live packager lists a sliding window of entries. Each listed segment is fetched once it is complete.
        val plan = planned(
            mpd(
                type = "dynamic",
                availabilityStart = "1970-01-01T00:16:40Z",
                body = timelineSet("""<S t="0" d="3000" r="2"/>""", media = "chunk-@Number%05d@.m4s"),
            ),
        )
        assertEquals(DashTiming.Live(1_000.0), plan.timing)
        assertTrue(plan.listedTimed)
        assertNull(plan.segmentCount)
        val listed = plan.listedSegments!!
        assertEquals(listOf(0L, 3_000L, 6_000L), listed.map { it.id })
        assertEquals(listOf(3_000L, 6_000L, 9_000L), listed.map { it.endId })
        assertEquals("chunk-00002.m4s", listed[1].media)
    }

    @Test fun staticSegmentListIsPlannedWithItsByteRanges() {
        val plan = planned(mpd(body = listSet(base = "single.mp4", body = """<SegmentURL mediaRange="815-13589"/><SegmentURL mediaRange="13590-26479"/>""")))
        assertEquals(DashTiming.Static(9.0), plan.timing)
        assertEquals(2L, plan.segmentCount)
        assertEquals("single.mp4", plan.representationBaseUrl)
        assertEquals(HlsByteRange(length = 815, offset = 0), plan.listedInitialization?.range)
        assertEquals(HlsByteRange(length = 12775, offset = 815), plan.listedSegments?.get(0)?.range)
        assertEquals(HlsByteRange(length = 12890, offset = 13590), plan.listedSegments?.get(1)?.range)
    }

    @Test fun segmentListEntriesCanNameTheirOwnMediaUrl() {
        val plan = planned(mpd(body = listSet(body = """<SegmentURL media="a.m4s"/><SegmentURL media="b.m4s"/>""")))
        assertEquals("a.m4s", plan.listedSegments?.get(0)?.media)
        assertNull(plan.listedSegments?.get(0)?.range)
        assertEquals(2L, plan.segmentCount)
    }

    @Test fun liveSegmentListIsPlannedForRefreshWithItsNumbers() {
        val text = mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z", body = listSet(base = "x.mp4", body = """<SegmentURL mediaRange="0-9"/><SegmentURL mediaRange="10-19"/>"""))
        val plan = planned(text)
        assertTrue(plan.timing is DashTiming.Live)
        assertNull(plan.segmentCount)
        assertEquals(1L, plan.listedSegments?.get(0)?.id)
        assertEquals(2L, plan.listedSegments?.get(1)?.id)
        assertEquals(3L, plan.listedSegments?.get(1)?.endId)
    }

    @Test fun liveTimingReadsTheMinimumUpdatePeriod() {
        val text = mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z", body = videoSet)
            .replace("availabilityStartTime=", "minimumUpdatePeriod=\"PT4S\" availabilityStartTime=")
        assertEquals(4.0, (planned(text).timing as DashTiming.Live).minimumUpdatePeriodSeconds)
        assertEquals(DashTiming.DEFAULT_MINIMUM_UPDATE_SECONDS, (planned(mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z")).timing as DashTiming.Live).minimumUpdatePeriodSeconds)
    }

    @Test fun segmentListRangeMustBeOrderedAndNumeric() {
        assertTrue(refusalOf(mpd(body = listSet(base = "x.mp4", body = """<SegmentURL mediaRange="10-5"/>"""))).contains("byte range"))
        assertTrue(refusalOf(mpd(body = listSet(base = "x.mp4", body = """<SegmentURL mediaRange="ten-20"/>"""))).contains("byte range"))
    }

    @Test fun segmentURLWithoutAnyLocationIsRefused() {
        assertTrue(refusalOf(mpd(body = listSet(body = """<SegmentURL/>"""))).contains("media"))
    }

    @Test fun emptySegmentListIsRefused() {
        assertTrue(refusalOf(mpd(body = listSet(body = ""))).contains("no segments"))
    }

    @Test fun presentationTimeOffsetPlacesAFixedDurationTemplateAtItsMediaStart() {
        val plan = planned(
            mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z",
                body = videoSet.replace("startNumber=\"1\"", "startNumber=\"1\" presentationTimeOffset=\"600000\"")),
        )
        assertEquals(600000L, plan.startTimeTicks)
        assertEquals(600000L, plan.presentationTimeOffsetTicks)
    }

    @Test fun presentationTimeOffsetIsReadFromALiveTimeline() {
        val plan = planned(
            mpd(
                type = "dynamic",
                availabilityStart = "1970-01-01T00:16:40Z",
                body = timelineSet("""<S t="600000" d="3000" r="-1"/>""")
                    .replace("startNumber=\"1\"", "startNumber=\"1\" presentationTimeOffset=\"600000\""),
            ),
        )
        assertEquals(600000L, plan.startTimeTicks)
        assertEquals(600000L, plan.presentationTimeOffsetTicks)
        assertEquals("chunk-600000.m4s", DashManifest.expand(plan.mediaTemplate, plan.representationId, plan.bandwidth, 1, timeOf(plan, 1)))
    }

    @Test fun aFfmpegSingleFileManifestIsPlannedAsByteRanges() {
        // Real output of ffmpeg -single_file 1: one MP4 whose SegmentList names its initialization and fragments by range.
        val text = DashManifestTest::class.java.getResource("/dash/ffmpeg-single-file.mpd")!!.readText()
        val plan = planned(text)
        assertEquals(3L, plan.segmentCount)
        assertEquals("single-stream0.mp4", plan.representationBaseUrl)
        assertEquals(HlsByteRange(length = 815, offset = 0), plan.listedInitialization?.range)
        assertEquals(HlsByteRange(length = 12775, offset = 815), plan.listedSegments?.get(0)?.range)
    }

    /** A video set addressed by SegmentBase: one media file, whose segment index is named by [indexRange]. */
    private fun baseSet(
        indexRange: String = "1000-1043",
        initRange: String = "0-999",
        base: String? = "single.mp4",
    ): String = """    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.64000a" width="160" height="120">
        ${base?.let { "<BaseURL>$it</BaseURL>" }.orEmpty()}
        <SegmentBase indexRange="$indexRange"><Initialization range="$initRange"/></SegmentBase>
      </Representation>
    </AdaptationSet>"""

    private fun needsIndexOf(text: String): DashPlanOutcome.NeedsIndex {
        val outcome = DashManifest.plan(text)
        assertTrue(outcome is DashPlanOutcome.NeedsIndex, "expected an index request, got $outcome")
        return outcome as DashPlanOutcome.NeedsIndex
    }

    @Test fun aSegmentBaseAsksForItsIndexRange() {
        val needs = needsIndexOf(mpd(body = baseSet()))
        assertEquals(HlsByteRange(length = 44, offset = 1000), needs.indexRange)
        assertEquals(HlsByteRange(length = 1000, offset = 0), needs.partial.listedInitialization?.range)
        assertEquals("single.mp4", needs.partial.representationBaseUrl)
        assertEquals(DashTiming.Static(9.0), needs.partial.timing)
    }

    @Test fun aSegmentBaseIsCompletedFromItsIndexBytes() {
        // Two fragments of 500 and 700 bytes follow the index box, which the index range names exactly.
        val index = SidxBox.build(references = listOf(500L to 2_000L, 700L to 2_000L))
        val end = 1000 + index.size - 1
        val needs = needsIndexOf(mpd(body = baseSet(indexRange = "1000-$end")))
        val plan = (DashManifest.completeIndex(needs, index) as DashPlanOutcome.Planned).plan
        val anchor = 1000L + index.size
        assertEquals(
            listOf(HlsByteRange(length = 500, offset = anchor), HlsByteRange(length = 700, offset = anchor + 500)),
            plan.listedSegments!!.map { it.range },
        )
        assertEquals(2L, plan.segmentCount)
        assertEquals(HlsByteRange(length = 1000, offset = 0), plan.listedInitialization?.range)
        assertEquals(DashTiming.Static(9.0), plan.timing)
    }

    @Test fun aSegmentBaseWithAnIndexLargerThanTheLimitIsRefused() {
        assertTrue(refusalOf(mpd(body = baseSet(indexRange = "0-200000"))).contains("too large"))
    }

    @Test fun aLiveSegmentBaseIsRefused() {
        val text = mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z", body = baseSet())
        assertTrue(refusalOf(text).contains("live SegmentBase"))
    }

    @Test fun aSegmentBaseNeedsItsInitializationRange() {
        val body = """    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.64000a" width="160" height="120">
        <BaseURL>single.mp4</BaseURL>
        <SegmentBase indexRange="1000-1043"/>
      </Representation>
    </AdaptationSet>"""
        assertTrue(refusalOf(mpd(body = body)).contains("Initialization"))
    }

    @Test fun aSegmentBaseNeedsAMediaBaseUrl() {
        assertTrue(refusalOf(mpd(body = baseSet(base = null))).contains("BaseURL"))
    }

    @Test fun anIndexThatDoesNotParseFailsTheCompletion() {
        val hierarchical = SidxBox.build(references = listOf(500L to 2_000L), hierarchicalFirst = true)
        val needs = needsIndexOf(mpd(body = baseSet(indexRange = "1000-${1000 + hierarchical.size - 1}")))
        val outcome = DashManifest.completeIndex(needs, hierarchical)
        assertTrue(outcome is DashPlanOutcome.Unsupported && outcome.reason.contains("hierarchical"), "got $outcome")
    }
}

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

    private fun refusalOf(text: String): String {
        val outcome = DashManifest.plan(text)
        assertTrue(outcome is DashPlanOutcome.Unsupported, "expected a refusal, got $outcome")
        return (outcome as DashPlanOutcome.Unsupported).reason
    }

    @Test fun planCarriesTheRepresentationAndItsTemplates() {
        val outcome = DashManifest.plan(mpd())
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        val plan = (outcome as DashPlanOutcome.Planned).plan
        assertEquals("v1", plan.representationId)
        assertEquals("init-\$RepresentationID\$.m4s", plan.initializationTemplate)
        assertEquals("chunk-\$RepresentationID\$-\$Number%05d\$.m4s", plan.mediaTemplate)
        assertEquals(3.0, plan.segmentDurationSeconds)
        assertEquals(DashTiming.Static(9.0), plan.timing)
        assertEquals(1L, plan.startNumber)
        assertNull(plan.baseUrl)
    }

    @Test fun liveManifestWithAnAvailabilityStartIsPlanned() {
        val outcome = DashManifest.plan(mpd(type = "dynamic", availabilityStart = "1970-01-01T00:16:40Z"))
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        assertEquals(DashTiming.Live(1_000.0), (outcome as DashPlanOutcome.Planned).plan.timing)
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

    @Test fun protectionTimelinesAndListsAreRefused() {
        val protected = videoSet.replace(
            "<Representation", "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\"/>\n      <Representation",
        )
        assertTrue(refusalOf(mpd(body = protected)).contains("ContentProtection"))
        val timeline = videoSet.replace(
            "/>\n      <Representation", ">\n<SegmentTimeline><S t=\"0\" d=\"3000\"/></SegmentTimeline></SegmentTemplate>\n      <Representation",
        )
        assertTrue(refusalOf(mpd(body = timeline)).contains("SegmentTimeline"))
    }

    @Test fun aDocumentTypeIsRejectedBeforeAnyParserSeesIt() {
        val text = "<!DOCTYPE MPD [ <!ENTITY x \"y\"> ]>\n" + mpd()
        assertTrue(refusalOf(text).contains("DTD"))
    }

    @Test fun notWellFormedXmlIsRefused() {
        assertEquals("DASH manifest is not well formed", refusalOf("<MPD>"))
    }

    @Test fun aTemplateWithAnUnsupportedTokenIsRefused() {
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
}

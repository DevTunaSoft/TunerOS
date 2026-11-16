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

    /** `@` stands for a dollar sign, so the fixtures read like manifests without Kotlin template escapes. */
    private fun mpd(
        type: String = "static",
        duration: String = "PT9S",
        baseUrl: String? = null,
        body: String = videoSet,
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" mediaPresentationDuration="$duration">
${baseUrl?.let { "  <BaseURL>$it</BaseURL>\n" }.orEmpty()}  <Period id="0" start="PT0S">
$body
  </Period>
</MPD>
""".replace('@', '$')

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
        assertEquals(9.0, plan.mediaPresentationDurationSeconds)
        assertEquals(1L, plan.startNumber)
        assertNull(plan.baseUrl)
    }

    @Test fun liveManifestsAreRefusedForNow() {
        assertEquals("Live DASH recording is not supported yet", refusalOf(mpd(type = "dynamic")))
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

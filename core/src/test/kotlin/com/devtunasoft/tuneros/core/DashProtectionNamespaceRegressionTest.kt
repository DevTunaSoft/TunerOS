package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CORE-41 audit slice: the DASH planner's protection refusal must not depend on an XML prefix.
 *
 * [DashManifest] parses with a namespace-unaware DOM, so a prefixed protection element has a literal
 * tag name such as "cenc:ContentProtection". A manifest that declares encrypted media must be refused
 * whichever prefix its author chose; otherwise the recorder plans and writes protected segments.
 */
class DashProtectionNamespaceRegressionTest {
    /** A valid static video manifest; `@` stands for a dollar sign in the template. */
    private fun mpd(extraElement: String = ""): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" duration="3000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="chunk-@RepresentationID@-@Number%05d@.m4s"/>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
      $extraElement
    </AdaptationSet>
  </Period>
</MPD>
""".replace('@', '$')

    private fun assertProtectionRefused(text: String, label: String) {
        val outcome = DashManifest.plan(text)
        assertTrue(outcome is DashPlanOutcome.Unsupported, "$label: expected a refusal, got $outcome")
        assertTrue(
            (outcome as DashPlanOutcome.Unsupported).reason.contains("ContentProtection"),
            "$label: refusal must name the protection rule: ${outcome.reason}",
        )
    }

    @Test fun theFixtureWithoutProtectionIsPlanned() {
        val outcome = DashManifest.plan(mpd())
        assertTrue(outcome is DashPlanOutcome.Planned, "the fixture must be valid: $outcome")
    }

    @Test fun aDeclaredCencPrefixOnContentProtectionIsRefused() {
        val text = mpd(
            """<cenc:ContentProtection xmlns:cenc="urn:mpeg:cenc:2013" schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>""",
        )
        assertProtectionRefused(text, "declared cenc prefix")
    }

    @Test fun anUndeclaredPrefixOnContentProtectionIsRefused() {
        val text = mpd("""<drm:ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011"/>""")
        assertProtectionRefused(text, "undeclared prefix")
    }

    @Test fun anArbitraryPrefixOnContentProtectionIsRefused() {
        val text = mpd("""<x:ContentProtection xmlns:x="urn:example:protection"/>""")
        assertProtectionRefused(text, "arbitrary prefix")
    }

    @Test fun anElementNameThatOnlyEndsWithProtectionIsStillPlanned() {
        val outcome = DashManifest.plan(mpd("""<NotContentProtection/>"""))
        assertTrue(outcome is DashPlanOutcome.Planned, "unrelated elements must not be refused: $outcome")
    }
}

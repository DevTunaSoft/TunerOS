package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded adversarial coverage for DASH timing magnitudes. An ISO-8601 duration can parse successfully and still be
 * too large to convert to milliseconds. The planner must answer with a fixed refusal or its documented default, never
 * by letting a JVM arithmetic exception escape.
 *
 * Fixtures use relative segment names only: no provider URL, credential or media payload is involved.
 */
class DashDurationOverflowTest {
    /** `@` stands for a dollar sign, so the fixture reads like real DASH template text. */
    private val videoSet = """    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" duration="3000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="chunk-@Number%05d@.m4s"/>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>"""

    /** Parses on JVM 17, but its millisecond value overflows a Long. */
    private val overLarge = "P106751991167300D"

    private fun staticMpd(duration: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="$duration">
  <Period id="0" start="PT0S">
$videoSet
  </Period>
</MPD>
""".replace('@', '$')

    private fun liveMpd(updatePeriod: String?): String {
        val attr = updatePeriod?.let { " minimumUpdatePeriod=\"$it\"" }.orEmpty()
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="1970-01-01T00:16:40Z"$attr>
  <Period id="0" start="PT0S">
$videoSet
  </Period>
</MPD>
""".replace('@', '$')
    }

    private fun refusal(text: String): String {
        val outcome = DashManifest.plan(text)
        assertTrue(outcome is DashPlanOutcome.Unsupported, "expected a refusal, got $outcome")
        return (outcome as DashPlanOutcome.Unsupported).reason
    }

    @Test fun overLargeStaticPresentationDurationIsRefusedWithoutThrowing() {
        assertEquals(
            "Static DASH manifest needs a presentation duration",
            refusal(staticMpd(overLarge)),
        )
    }

    @Test fun overLargeMinimumUpdatePeriodFallsBackToDefault() {
        val outcome = DashManifest.plan(liveMpd(overLarge))
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        val timing = (outcome as DashPlanOutcome.Planned).plan.timing as DashTiming.Live
        assertEquals(DashTiming.DEFAULT_MINIMUM_UPDATE_SECONDS, timing.minimumUpdatePeriodSeconds)
    }

    @Test fun ordinaryDurationStillPlans() {
        val outcome = DashManifest.plan(staticMpd("PT9S"))
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        assertEquals(DashTiming.Static(9.0), (outcome as DashPlanOutcome.Planned).plan.timing)
    }

    @Test fun representableDurationStillUsesTheExistingSegmentLimit() {
        // 1e12 seconds converts to milliseconds without overflow, so this must keep the ordinary refusal path.
        assertEquals(
            "DASH manifest has too many segments",
            refusal(staticMpd("PT1000000000000S")),
        )
    }

    @Test fun overLargeNegativeDurationIsRefusedWithoutThrowing() {
        // The negative guard already short-circuits before conversion; this is a control against over-broad changes.
        assertEquals(
            "Static DASH manifest needs a presentation duration",
            refusal(staticMpd("P-106751991167300D")),
        )
    }
}

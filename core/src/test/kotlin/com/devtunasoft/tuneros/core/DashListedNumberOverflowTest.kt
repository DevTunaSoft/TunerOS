package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CORE-36: adversarial segment-number bounds for DASH *listed* addressing.
 *
 * CORE-35 bounded the SegmentTemplate path (startNumber + segmentCount - 1 and the recorder's
 * increment). A SegmentList and a listed SegmentTimeline assign numbers with `startNumber + index`
 * and end identities with `number + 1`; those additions are a separate seam and can still wrap at
 * Long.MAX_VALUE. A wrapped identity is not a valid segment number, and the documented contract of
 * [DashListedSegment] requires identities to increase along the listing.
 *
 * All addresses below are synthetic relative names or non-routable example.test names.
 */
class DashListedNumberOverflowTest {
    private fun listedSet(startNumber: Long): String = """    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120">
        <SegmentList timescale="1000" duration="3000" startNumber="$startNumber">
          <Initialization sourceURL="init.m4s"/>
          <SegmentURL media="chunk-a.m4s"/>
          <SegmentURL media="chunk-b.m4s"/>
        </SegmentList>
      </Representation>
    </AdaptationSet>"""

    private fun mpd(startNumber: Long, dynamic: Boolean): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="${if (dynamic) "dynamic" else "static"}" ${
        if (dynamic) "availabilityStartTime=\"1970-01-01T00:16:40Z\"" else "mediaPresentationDuration=\"PT6S\""
    }>
  <Period id="0" start="PT0S">
${listedSet(startNumber)}
  </Period>
</MPD>
"""

    private fun refusalOf(text: String): String {
        val outcome = DashManifest.plan(text)
        assertTrue(outcome is DashPlanOutcome.Unsupported, "expected a refusal, got $outcome")
        return (outcome as DashPlanOutcome.Unsupported).reason
    }

    @Test fun aStaticSegmentListWhoseLastIdentityOverflowsIsRefusedBeforeAnyByteIsWritten() {
        // Two segments need numbers MAX-1 and MAX; the second one's end identity would wrap to MIN.
        assertEquals(
            "DASH segment numbers overflow",
            refusalOf(mpd(Long.MAX_VALUE - 1, dynamic = false)),
        )
    }

    @Test fun aLiveSegmentListWhoseLastIdentityOverflowsIsRefused() {
        assertEquals(
            "DASH segment numbers overflow",
            refusalOf(mpd(Long.MAX_VALUE - 1, dynamic = true)),
        )
    }

    private fun listedTimeline(startNumber: Long): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="1970-01-01T00:16:40Z">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120">
        <SegmentTemplate timescale="1000" startNumber="$startNumber" initialization="init.m4s" media="chunk-@Number@.m4s">
          <SegmentTimeline><S t="0" d="3000" r="1"/></SegmentTimeline>
        </SegmentTemplate>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
""".replace('@', '$')

    @Test fun aLiveListedTimelineWhoseLastIdentityOverflowsIsRefused() {
        // A live timeline window is listed segment by segment and numbered from startNumber as well.
        assertEquals(
            "DASH segment numbers overflow",
            refusalOf(listedTimeline(Long.MAX_VALUE - 1)),
        )
    }

    @Test fun theHighestRepresentableSegmentListKeepsIncreasingIdentities() {
        // MAX-2 and MAX-1 are both usable numbers, and the last end identity is exactly MAX.
        val outcome = DashManifest.plan(mpd(Long.MAX_VALUE - 2, dynamic = false))
        assertTrue(outcome is DashPlanOutcome.Planned, "expected a plan, got $outcome")
        val segments = (outcome as DashPlanOutcome.Planned).plan.listedSegments!!
        assertEquals(listOf(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1), segments.map { it.id })
        assertEquals(listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE), segments.map { it.endId })
    }
}

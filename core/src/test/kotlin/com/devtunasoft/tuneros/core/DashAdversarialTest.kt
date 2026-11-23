package com.devtunasoft.tuneros.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic adversarial regression coverage for the DASH slice. Every case is seeded or a fixed byte/string, so a
 * failure reproduces exactly. Byte-range arithmetic is checked at its bounds and at overflow; malformed and truncated
 * index bytes must fail closed; a plan must stay bounded and never echo manifest text in a diagnostic.
 */
class DashAdversarialTest {
    private fun parseAt(bytes: ByteArray, offset: Long): DashSegmentIndex.Outcome =
        DashSegmentIndex.parse(bytes, HlsByteRange(length = bytes.size.toLong(), offset = offset))

    private fun singleReferenceIndex(): ByteArray = SidxBox.build(references = listOf(100L to 1_000L))

    private fun defaultBody(): String = """<AdaptationSet mimeType="video/mp4">
<SegmentTemplate timescale="1000" duration="3000" startNumber="1" media="seg-@Number@.m4s" initialization="init.mp4"/>
<Representation id="v1" bandwidth="500000" codecs="avc1.42c01e"/>
</AdaptationSet>"""

    /** `@` stands for a dollar sign so the fixture reads like a manifest. */
    private fun mpd(duration: String = "PT9S", body: String = defaultBody()): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="$duration">
<Period id="0" start="PT0S">
$body
</Period>
</MPD>""".replace('@', '$')

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

    // ---------- DashSegmentIndex: arithmetic bounds and overflow ----------

    @Test
    fun anIndexRangeOffsetThatOverflowsWithTheBoxSizeIsRefused() {
        // The anchor is offset + boxSize + first_offset. With offset near Long.MAX_VALUE the unchecked inner addition
        // wraps negative, so the upper-bound check alone cannot fail closed. The parser must refuse instead of parsing.
        val outcome = parseAt(singleReferenceIndex(), Long.MAX_VALUE - 10)
        assertTrue(
            outcome is DashSegmentIndex.Outcome.Refused && outcome.reason.contains("overflow"),
            "an overflowing index range must be refused, got $outcome",
        )
    }

    @Test
    fun anIndexRangeOffsetThatMakesTheAnchorNegativeIsRefused() {
        // -1000 + a 44-byte box is still negative, so no upper-bound check catches it. No parsed reference may name a
        // negative byte position.
        val outcome = parseAt(singleReferenceIndex(), -1_000)
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused, "a negative anchor must be refused, got $outcome")
    }

    @Test
    fun aReferenceThatCrossesTheByteBoundMidListIsRefused() {
        // The first reference ends just inside MAX_BYTE; the second would run past it. Neither may be returned parsed.
        val maxByte = 1_000_000_000_000_000L
        val box = SidxBox.build(
            version = 1,
            firstOffset = maxByte - 214,
            references = listOf(100L to 1_000L, 200L to 1_000L),
        )
        val outcome = DashSegmentIndex.parse(box, HlsByteRange(length = box.size.toLong(), offset = 0))
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused, "got $outcome")
    }

    // ---------- DashSegmentIndex: malformed and truncated bytes ----------

    @Test
    fun everyTruncationOfAValidIndexIsRefusedWithoutAnException() {
        val bytes = SidxBox.build(references = listOf(500L to 2_000L, 700L to 2_000L, 600L to 3_000L))
        for (length in 0 until bytes.size) {
            val outcome = parseAt(bytes.copyOf(length), 1_000)
            assertTrue(
                outcome is DashSegmentIndex.Outcome.Refused,
                "a $length-byte prefix must be refused, got $outcome",
            )
        }
    }

    @Test
    fun aLargeSizeBoxWithATruncatedExtendedSizeIsRefused() {
        // Size 1 means a 64-bit largesize follows. Only the base header is present, so the extended field is truncated.
        val bytes = ByteBuffer.allocate(12)
            .putInt(1)
            .put("sidx".toByteArray(Charsets.US_ASCII))
            .put(ByteArray(4))
            .array()
        val outcome = DashSegmentIndex.parse(bytes, HlsByteRange(length = bytes.size.toLong(), offset = 0))
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused, "got $outcome")
    }

    @Test
    fun aZeroDeclaredBoxSizeIsRefused() {
        val bytes = singleReferenceIndex()
        ByteBuffer.wrap(bytes).putInt(0, 0)
        val outcome = parseAt(bytes, 1_000)
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused, "got $outcome")
    }

    // ---------- DashManifest: bounded counts, boundaries, fail-closed diagnostics ----------

    @Test
    fun aManifestBeyondTheCharacterLimitIsRefused() {
        val outcome = DashManifest.plan("<MPD>" + " ".repeat(1_000_000))
        assertTrue(outcome is DashPlanOutcome.Unsupported && outcome.reason.contains("too large"), "got $outcome")
    }

    @Test
    fun aPresentationWithNoWholeSegmentIsRefused() {
        assertTrue(refusalOf(mpd(duration = "PT0S")).contains("no media segments"))
    }

    @Test
    fun aPresentationOfExactlyOneSegmentIsPlanned() {
        val plan = planned(mpd(duration = "PT3S"))
        assertEquals(1L, plan.segmentCount)
        assertNull(plan.listedSegments)
    }

    @Test
    fun theLargestAllowedSegmentCountIsPlannedWithoutRetainingAListing() {
        // 100,000 segments of 3 s. Counting is arithmetic, so even the maximum must not retain a per-segment list.
        val plan = planned(mpd(duration = "PT300000S"))
        assertEquals(100_000L, plan.segmentCount)
        assertNull(plan.listedSegments)
    }

    @Test
    fun aSegmentCountAboveTheMaximumIsRefused() {
        assertTrue(refusalOf(mpd(duration = "PT300004S")).contains("too many"))
    }

    @Test
    fun malformedIndexBytesFailClosedThroughCompleteIndex() {
        val body = """<AdaptationSet mimeType="video/mp4">
<Representation id="v1" bandwidth="500000" codecs="avc1.42c01e">
<BaseURL>media.mp4</BaseURL>
<SegmentBase indexRange="0-9"><Initialization range="0-4"/></SegmentBase>
</Representation>
</AdaptationSet>"""
        val outcome = DashManifest.plan(mpd(body = body))
        assertTrue(outcome is DashPlanOutcome.NeedsIndex, "got $outcome")
        val request = outcome as DashPlanOutcome.NeedsIndex
        for (indexBytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(100))) {
            val completed = DashManifest.completeIndex(request, indexBytes)
            assertTrue(completed is DashPlanOutcome.Unsupported, "got $completed")
        }
    }

    @Test
    fun aRefusalNeverEchoesManifestTextOrFixtureSecrets() {
        val secret = "fixture-secret-should-not-appear"
        val body = """<AdaptationSet mimeType="video/mp4">
<ContentProtection schemeIdUri="$secret" customToken="$secret"/>
<Representation id="v1" bandwidth="500000"/>
</AdaptationSet>"""
        val reason = refusalOf(mpd(body = body))
        assertTrue(secret !in reason, "a diagnostic echoed manifest text: $reason")
    }
}

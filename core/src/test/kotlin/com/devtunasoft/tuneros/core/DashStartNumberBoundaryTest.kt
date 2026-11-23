package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic boundary coverage for static DASH segment numbers. The planner accepts any non-negative
 * startNumber, and the recorder computes the last segment as startNumber + segmentCount - 1 and then
 * increments the number after every segment. Both can cross Long.MAX_VALUE. All addresses below are
 * synthetic relative names or non-routable example.test names; no provider data appears here.
 */
class DashStartNumberBoundaryTest {
    private val manifestUrl = "https://example.test/dash/manifest.mpd"
    private val initUrl = "https://example.test/dash/init.m4s"

    private fun manifest(startNumber: Long): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT6S">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" duration="3000" startNumber="$startNumber" initialization="init.m4s" media="chunk-@Number@.m4s"/>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e"/>
    </AdaptationSet>
  </Period>
</MPD>
""".replace('@', '$')

    private fun segmentUrl(number: Long): String = "https://example.test/dash/chunk-$number.m4s"

    /** Bounds the accidental extra requests an overflowing segment counter would produce. */
    private class BoundedSource(
        private val manifestUrl: String,
        private val manifest: String,
        expected: Set<String>,
        private val initUrl: String,
    ) : DashCaptureSource {
        val copied = ArrayList<String>()
        private val bytes = mapOf(initUrl to byteArrayOf(9)) + expected.associateWith { byteArrayOf(1) }

        override fun loadManifest(url: String): LoadedDashManifest = LoadedDashManifest(manifestUrl, manifest)
        override fun loadRange(url: String, range: HlsByteRange): ByteArray = error("no index in this fixture")
        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            check(copied.size <= 4) { "too many segment requests" }
            val payload = bytes[url] ?: error("unexpected segment request")
            output.write(payload)
            onProgress(payload.size.toLong())
            return StreamRecorder.Result(payload.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    @Test fun aStaticPlanWhoseLastSegmentNumberOverflowsIsRefusedBeforeAnyByteIsWritten() {
        val outcome = DashManifest.plan(manifest(Long.MAX_VALUE))
        assertTrue(outcome is DashPlanOutcome.Unsupported, "expected a refusal, got $outcome")
        assertEquals("DASH segment numbers overflow", (outcome as DashPlanOutcome.Unsupported).reason)
    }

    @Test fun theHighestRepresentableRangeRecordsBothSegmentsAndThenStops() {
        // MAX - 1 plus two segments ends exactly at MAX; there is no valid segment after it.
        val first = Long.MAX_VALUE - 1
        val expected = setOf(segmentUrl(first), segmentUrl(Long.MAX_VALUE))
        val source = BoundedSource(manifestUrl, manifest(first), expected, initUrl)
        val out = ByteArrayOutputStream()
        val result = DashCaptureEngine().record(
            manifestUrl, out, 1_000, source, { false }, { 100 }, {},
        )
        assertEquals(DashCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals(listOf(9, 1, 1), out.toByteArray().map { it.toInt() })
        assertEquals(listOf(initUrl, segmentUrl(first), segmentUrl(Long.MAX_VALUE)), source.copied)
        assertEquals(3L, result.bytesWritten)
    }
}

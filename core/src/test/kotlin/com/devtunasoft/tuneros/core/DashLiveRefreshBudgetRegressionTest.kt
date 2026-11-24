package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded-liveness coverage for the refresh interval of a listed live DASH recording.
 *
 * The planner accepts any positive xs:duration as `minimumUpdatePeriod`, but the recorder must not turn a
 * hostile sub-second value into a rapid manifest-poll loop, nor a very long value into an unbounded stall.
 * No sibling CORE branch asserts the observable sleep interval; they cover the planner's parsed value and
 * the stall/outage decisions, not the clamp that bounds network and CPU work. Fixtures use only synthetic
 * addresses under `.invalid` and invented bytes: no provider URL, credential or media payload.
 */
class DashLiveRefreshBudgetRegressionTest {
    private val manifestUrl = "https://synthetic.invalid/dash/live.mpd"
    private val initUrl = "https://synthetic.invalid/dash/init.m4s"
    private val chunk1 = "https://synthetic.invalid/dash/chunk-1.m4s"
    private val chunk2 = "https://synthetic.invalid/dash/chunk-2.m4s"

    private class Clock(seconds: Long) {
        var millis: Long = seconds * 1000L
        fun now(): Long = millis / 1000L
    }

    private class ListingSource(
        private val manifestUrl: String,
        private val text: String,
        private val bytes: Map<String, ByteArray>,
    ) : DashCaptureSource {
        val copied = ArrayList<String>()
        var manifestReads = 0

        override fun loadManifest(url: String): LoadedDashManifest {
            manifestReads++
            return LoadedDashManifest(manifestUrl, text)
        }

        override fun loadRange(url: String, range: HlsByteRange): ByteArray = error("no sidx in this fixture")

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            val payload = bytes[url] ?: error("no synthetic bytes for $url")
            output.write(payload)
            onProgress(payload.size.toLong())
            return StreamRecorder.Result(payload.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    /** A dynamic SegmentList of two invented segments. [period] is inserted verbatim as the MPD attribute. */
    private fun manifest(period: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="1970-01-01T00:16:40Z"$period>
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120">
        <SegmentList timescale="1000" duration="3000" startNumber="1">
          <Initialization sourceURL="init.m4s"/>
          <SegmentURL media="chunk-1.m4s"/>
          <SegmentURL media="chunk-2.m4s"/>
        </SegmentList>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""

    private val syntheticBytes = mapOf(
        initUrl to byteArrayOf(9, 9),
        chunk1 to byteArrayOf(1),
        chunk2 to byteArrayOf(2),
    )

    /** Records until the first refresh sleep has moved the clock to the deadline, and returns the sleeps. */
    private fun firstRefreshSleep(period: String, deadlineSeconds: Long): List<Long> {
        val clock = Clock(1_000L)
        val source = ListingSource(manifestUrl, manifest(period), syntheticBytes)
        val sleeps = ArrayList<Long>()
        val result = DashCaptureEngine().record(
            initialUrl = manifestUrl,
            output = ByteArrayOutputStream(),
            endEpochSeconds = deadlineSeconds,
            source = source,
            cancelled = { false },
            nowEpochSeconds = { clock.now() },
            sleepMillis = { delay -> sleeps += delay; clock.millis += delay },
        )
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertTrue(source.manifestReads >= 2, "the refresh sleep must precede at least one manifest reread")
        // A listed live presentation starts at the newest listed segment, so only the last one is fetched here.
        assertEquals(listOf(initUrl, chunk2), source.copied)
        return sleeps
    }

    @Test fun aSubSecondMinimumUpdatePeriodIsClampedToTheOneSecondFloor() {
        val sleeps = firstRefreshSleep(" minimumUpdatePeriod=\"PT0.05S\"", deadlineSeconds = 1_001L)
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test fun aLongMinimumUpdatePeriodIsClampedToTheTenSecondCeiling() {
        val sleeps = firstRefreshSleep(" minimumUpdatePeriod=\"PT1M\"", deadlineSeconds = 1_010L)
        assertEquals(listOf(10_000L), sleeps)
    }

    @Test fun anInvalidMinimumUpdatePeriodUsesTheDocumentedTwoSecondDefault() {
        val sleeps = firstRefreshSleep(" minimumUpdatePeriod=\"not-a-duration\"", deadlineSeconds = 1_002L)
        assertEquals(listOf(2_000L), sleeps)
    }
}

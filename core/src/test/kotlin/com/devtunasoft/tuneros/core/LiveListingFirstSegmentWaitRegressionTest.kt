package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CORE-57: bounded, deterministic coverage of the DASH live-listing seam before the first segment is copied.
 *
 * The engine has two ways to wait for live media. A clock-computed live SegmentTemplate waits for the first complete
 * segment until the scheduled stop. A listed live timeline (irregular durations are listed rather than computed) must
 * wait the same way: its stall clock measures a listing that stopped *after* recording began, not a listing whose first
 * segment has not become complete yet. Sibling CORE branches cover the post-first-segment stall and the clock-computed
 * wait; none exercises the pre-first-segment listed path.
 *
 * Every address here is a reserved example.test name and every payload is two synthetic bytes. No provider URL,
 * credential, real media, clock or locale is used.
 */
class LiveListingFirstSegmentWaitRegressionTest {
    private companion object {
        const val MANIFEST_URL = "https://example.test/dash/manifest.mpd"
        const val INIT_URL = "https://example.test/dash/init-v1.m4s"
        const val SEGMENT_URL = "https://example.test/dash/chunk-0.m4s"
        // Epoch second 1000, the same synthetic availability start the existing live DASH tests use.
        const val LIVE_START = "1970-01-01T00:16:40Z"
        // A synthetic availability start still in the future at the fixture's clock (epoch 2000).
        const val FUTURE_START = "1970-01-01T00:33:20Z"
    }

    private class Clock(seconds: Long) {
        var millis: Long = seconds * 1_000L
        fun now(): Long = millis / 1_000L
        fun sleep(delayMillis: Long) { millis += delayMillis }
    }

    private class Source(
        private val manifest: String,
        private val bytes: Map<String, ByteArray>,
        private val clock: Clock,
    ) : DashCaptureSource {
        val copied = ArrayList<String>()
        var manifestReads = 0
            private set

        override fun loadManifest(url: String): LoadedDashManifest {
            manifestReads++
            return LoadedDashManifest(MANIFEST_URL, manifest)
        }

        override fun loadRange(url: String, range: HlsByteRange): ByteArray = error("no index in this fixture")

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            copied += url
            val payload = bytes[url] ?: error("unexpected segment request")
            output.write(payload)
            onProgress(payload.size.toLong())
            return StreamRecorder.Result(payload.size.toLong(), StreamRecorder.StopReason.END_OF_STREAM)
        }
    }

    /** Two unequal durations force the listed-timeline path; the first 40 s segment completes at epoch 1040. */
    private fun manifest(availabilityStart: String = LIVE_START): String = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="$availabilityStart">
  <Period id="0" start="PT0S">
    <AdaptationSet mimeType="video/mp4">
      <SegmentTemplate timescale="1000" startNumber="1" initialization="init-@RepresentationID@.m4s" media="chunk-@Time@.m4s">
        <SegmentTimeline><S t="0" d="40000"/><S d="30000"/></SegmentTimeline>
      </SegmentTemplate>
      <Representation id="v1" bandwidth="500000" codecs="avc1.42c01e" width="160" height="120"/>
    </AdaptationSet>
  </Period>
</MPD>
""".replace('@', '$')

    private fun run(
        startSecond: Long,
        endSecond: Long,
        availabilityStart: String = LIVE_START,
    ): Triple<DashCaptureEngine.Result, Source, Clock> {
        val clock = Clock(startSecond)
        val source = Source(
            manifest(availabilityStart),
            mapOf(INIT_URL to byteArrayOf(9, 9), SEGMENT_URL to byteArrayOf(1)),
            clock,
        )
        val out = ByteArrayOutputStream()
        val result = DashCaptureEngine().record(
            MANIFEST_URL, out, endSecond, source, { false }, clock::now, clock::sleep,
        )
        check(out.toByteArray().isNotEmpty()) { "the initialization must always be written" }
        return Triple(result, source, clock)
    }

    @Test fun aListedSegmentThatIsNotCompleteYetWaitsUntilTheScheduledStop() {
        val (result, source, clock) = run(startSecond = 1_000, endSecond = 1_035)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(INIT_URL), source.copied, "an incomplete segment must not be copied")
        assertTrue(clock.now() >= 1_035, "the recorder must keep polling to its stop")
        assertTrue(source.manifestReads > 1, "the live listing must be re-read while waiting")
    }

    @Test fun aFrozenListingBeforeItsFirstSegmentDoesNotHitThePostRecordingStallLimit() {
        // 1060 is 60 s after the fixture clock started and beyond the 30 s stall budget. Availability starts at 2000,
        // so no listed segment can be complete yet; the clock-computed live path has a wait-until-stop contract here.
        val (result, source, _) = run(
            startSecond = 1_000, endSecond = 1_060, availabilityStart = FUTURE_START,
        )
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(null, result.detail)
        assertEquals(listOf(INIT_URL), source.copied)
    }

    @Test fun aListingWhoseFirstSegmentBecameCompleteIsCopiedOnce() {
        val (result, source, _) = run(startSecond = 1_041, endSecond = 1_045)
        assertEquals(DashCaptureEngine.StopReason.DEADLINE, result.stopReason)
        assertEquals(listOf(INIT_URL, SEGMENT_URL), source.copied)
    }
}

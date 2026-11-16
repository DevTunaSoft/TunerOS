package com.devtunasoft.tuneros.core

import java.io.IOException
import java.io.OutputStream
import java.net.URI

data class LoadedDashManifest(
    val effectiveUrl: String,
    val text: String,
)

/**
 * Network access for DASH recording. Implementations keep provider headers, never log URLs or bodies, and
 * never follow a redirect to another scheme. A byte range, when given, is requested with a Range header and must
 * come back as exactly that range. A 404 answered before any body byte must be reported as
 * [DashSegmentNotPublishedException]: nothing has been written for that segment.
 */
interface DashCaptureSource {
    fun loadManifest(url: String): LoadedDashManifest

    fun copySegment(
        url: String,
        byteRange: HlsByteRange?,
        output: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamRecorder.Result
}

/** The origin answered 404 before the body. Live recording waits for such a segment within a grace window. */
class DashSegmentNotPublishedException : IOException("DASH segment is not published")

/**
 * Records one DASH video representation: the initialization segment once, then each media segment in order.
 *
 * A static presentation stops after the last segment its manifest lists or its duration implies. A live presentation
 * follows the clock: it starts at the newest complete segment, waits until each following segment has elapsed, and
 * runs to the scheduled stop. A live segment the origin has not published yet is retried within a bounded grace
 * window. After that the recording stops with the bytes it has, so a gap is never written silently. A byte range that
 * arrives short stops the recording the same way. Anything the planner refuses is reported as UNSUPPORTED before any
 * byte is written.
 */
class DashCaptureEngine {
    enum class StopReason { END_LIST, CANCELLED, DEADLINE, LOW_STORAGE, UNSUPPORTED }

    data class Result(
        val bytesWritten: Long,
        val stopReason: StopReason,
        val detail: String? = null,
    )

    /** One request for a segment: its absolute address and, for a SegmentList, its byte range. */
    private class Request(val url: String, val range: HlsByteRange?)

    fun record(
        initialUrl: String,
        output: OutputStream,
        endEpochSeconds: Long,
        source: DashCaptureSource,
        cancelled: () -> Boolean,
        nowEpochSeconds: () -> Long,
        sleepMillis: (Long) -> Unit,
        onProgress: (Long) -> Unit = {},
    ): Result {
        require(endEpochSeconds > 0)
        if (cancelled()) return Result(0, StopReason.CANCELLED)
        if (nowEpochSeconds() >= endEpochSeconds) return Result(0, StopReason.DEADLINE)

        val loaded = source.loadManifest(initialUrl)
        val plan = when (val outcome = DashManifest.plan(loaded.text)) {
            is DashPlanOutcome.Unsupported -> return Result(0, StopReason.UNSUPPORTED, outcome.reason)
            is DashPlanOutcome.Planned -> outcome.plan
        }
        val timing = plan.timing
        // A static presentation ends at its last listed segment. A live one never does; the scheduled stop ends it.
        val lastNumber = plan.segmentCount?.let { plan.startNumber + it - 1 } ?: Long.MAX_VALUE

        // The manifest's BaseURL, then the representation's, each relative to the one before.
        var base = loaded.effectiveUrl
        for (relative in listOfNotNull(plan.baseUrl, plan.representationBaseUrl)) {
            base = resolveOrNull(base, relative) ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
        }

        var totalBytes = 0L
        val listedInitialization = plan.listedInitialization
        val initializationTemplate = plan.initializationTemplate
        if (listedInitialization != null || initializationTemplate != null) {
            val request = if (listedInitialization != null) {
                val source = listedInitialization.source
                val url = if (source == null) base else resolveOrNull(base, source)
                    ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
                Request(url, listedInitialization.range)
            } else {
                val expanded = DashManifest.expand(
                    initializationTemplate ?: "", plan.representationId, plan.bandwidth, plan.startNumber,
                    plan.startTimeTicks,
                ) ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_TOKEN)
                val url = resolveOrNull(base, expanded) ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
                Request(url, null)
            }
            val before = totalBytes
            val init = try {
                source.copySegment(request.url, request.range, output, cancelled) { bytes -> onProgress(before + bytes) }
            } catch (missing: DashSegmentNotPublishedException) {
                return Result(totalBytes, StopReason.UNSUPPORTED, UNAVAILABLE_INITIALIZATION)
            }
            totalBytes = Math.addExact(totalBytes, init.bytesWritten)
            when (init.stopReason) {
                StreamRecorder.StopReason.END_OF_STREAM -> {
                    if (init.bytesWritten == 0L) {
                        return Result(totalBytes, StopReason.UNSUPPORTED, "DASH initialization segment is empty")
                    }
                    if (request.range != null && init.bytesWritten != request.range.length) {
                        return Result(totalBytes, StopReason.UNSUPPORTED, incompleteRange(request.range, init.bytesWritten))
                    }
                    onProgress(totalBytes)
                }
                StreamRecorder.StopReason.CANCELLED -> return Result(totalBytes, StopReason.CANCELLED)
                StreamRecorder.StopReason.LOW_STORAGE -> return Result(totalBytes, StopReason.LOW_STORAGE)
            }
        }

        // A live recording starts at the newest complete segment, as the HLS live recorder starts at the last one.
        var number = when (timing) {
            is DashTiming.Static -> plan.startNumber
            is DashTiming.Live -> maxOf(plan.startNumber, newestCompleteNumber(timing, plan, nowEpochSeconds()))
        }
        while (true) {
            if (cancelled()) return Result(totalBytes, StopReason.CANCELLED)
            if (number > lastNumber) return Result(totalBytes, StopReason.END_LIST)
            val now = nowEpochSeconds()
            if (now >= endEpochSeconds) return Result(totalBytes, StopReason.DEADLINE)
            if (timing is DashTiming.Live && number > newestCompleteNumber(timing, plan, now)) {
                sleepMillis(LIVE_POLL_MILLIS)
                continue
            }
            val request = mediaRequest(plan, base, number)
                ?: return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_TOKEN)
            if (request.url.isEmpty()) return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
            val before = totalBytes
            val copy = try {
                source.copySegment(request.url, request.range, output, cancelled) { bytes -> onProgress(before + bytes) }
            } catch (missing: DashSegmentNotPublishedException) {
                if (timing is DashTiming.Live && withinPublishGrace(timing, plan, number, nowEpochSeconds())) {
                    sleepMillis(LIVE_POLL_MILLIS)
                    continue
                }
                val reason = if (timing is DashTiming.Live) UNPUBLISHED_LIVE_SEGMENT else UNAVAILABLE_SEGMENT
                return Result(totalBytes, StopReason.UNSUPPORTED, reason)
            }
            totalBytes = Math.addExact(totalBytes, copy.bytesWritten)
            when (copy.stopReason) {
                StreamRecorder.StopReason.END_OF_STREAM -> {
                    if (request.range != null && copy.bytesWritten != request.range.length) {
                        return Result(totalBytes, StopReason.UNSUPPORTED, incompleteRange(request.range, copy.bytesWritten))
                    }
                    onProgress(totalBytes)
                }
                StreamRecorder.StopReason.CANCELLED -> return Result(totalBytes, StopReason.CANCELLED)
                StreamRecorder.StopReason.LOW_STORAGE -> return Result(totalBytes, StopReason.LOW_STORAGE)
            }
            number++
        }
    }

    /**
     * The request for media segment [number]: a SegmentList entry with its byte range, or a SegmentTemplate expansion.
     * Returns null when a template token cannot be expanded. A URL that cannot be resolved has an empty [Request.url].
     */
    private fun mediaRequest(plan: DashPlan, base: String, number: Long): Request? {
        val listed = plan.listedSegments
        if (listed != null) {
            val entry = listed[(number - plan.startNumber).toInt()]
            val url = if (entry.media == null) base else resolveOrNull(base, entry.media) ?: ""
            return Request(url, entry.range)
        }
        val expanded = DashManifest.expand(
            plan.mediaTemplate, plan.representationId, plan.bandwidth, number, timeTicks(plan, number),
        ) ?: return null
        return Request(resolveOrNull(base, expanded) ?: "", null)
    }

    private fun incompleteRange(range: HlsByteRange, received: Long): String =
        "DASH byte range incomplete: expected ${range.length} bytes, received $received"

    /** Start time of segment [number] in timescale units: the first segment's start plus whole durations. */
    private fun timeTicks(plan: DashPlan, number: Long): Long =
        Math.addExact(plan.startTimeTicks, Math.multiplyExact(number - plan.startNumber, plan.segmentDurationTicks))

    /** The newest segment whose end time has passed at [nowEpochSeconds], measured from availabilityStart. */
    private fun newestCompleteNumber(timing: DashTiming.Live, plan: DashPlan, nowEpochSeconds: Long): Long {
        val elapsedTicks =
            (nowEpochSeconds - timing.availabilityStartEpochSeconds) * plan.timescale - plan.startTimeTicks
        return plan.startNumber + Math.floor(elapsedTicks / plan.segmentDurationTicks).toLong() - 1
    }

    /** Segment [number] is complete when its end time has passed: availabilityStart + (start + duration) / timescale. */
    private fun completeAtEpochSeconds(timing: DashTiming.Live, plan: DashPlan, number: Long): Double =
        timing.availabilityStartEpochSeconds +
            (timeTicks(plan, number) + plan.segmentDurationTicks).toDouble() / plan.timescale.toDouble()

    private fun withinPublishGrace(timing: DashTiming.Live, plan: DashPlan, number: Long, nowEpochSeconds: Long): Boolean =
        nowEpochSeconds - completeAtEpochSeconds(timing, plan, number) <
            maxOf(LIVE_PUBLISH_GRACE_SECONDS, 2 * plan.segmentDurationSeconds)

    /** Resolves [relative] against [base]. Null when the result is not HTTP(S) or would downgrade HTTPS. */
    private fun resolveOrNull(base: String, relative: String): String? = runCatching {
        val baseUri = URI(base)
        val resolved = baseUri.resolve(relative)
        val httpScheme = resolved.scheme.equals("http", ignoreCase = true) ||
            resolved.scheme.equals("https", ignoreCase = true)
        val downgrade = baseUri.scheme.equals("https", ignoreCase = true) &&
            resolved.scheme.equals("http", ignoreCase = true)
        if (!httpScheme || resolved.host.isNullOrBlank() || downgrade) null else resolved.toString()
    }.getOrNull()

    private companion object {
        /** How long a live segment may stay unpublished after its end time has passed. */
        const val LIVE_PUBLISH_GRACE_SECONDS = 10.0

        /** Live waits in half-second steps: prompt enough to stop on time, slow enough not to spin. */
        const val LIVE_POLL_MILLIS = 500L
        const val UNSUPPORTED_RESOURCE = "DASH resource must use HTTP or HTTPS"
        const val UNSUPPORTED_TOKEN = "DASH template uses a token that is not supported"
        const val UNAVAILABLE_INITIALIZATION = "DASH initialization segment is not available"
        const val UNAVAILABLE_SEGMENT = "DASH segment is not available"
        const val UNPUBLISHED_LIVE_SEGMENT = "DASH live segment was not published in time"
    }
}

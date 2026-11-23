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

    /**
     * The bytes of [range] in [url], for a SegmentBase index and for the one-byte reads that check the index covers its
     * file. All are read before any byte is written, and the length of a read is bounded by the index limit. A range the
     * origin does not answer throws: for the index that marks it unavailable, and for a one-byte read that marks the byte
     * absent.
     */
    fun loadRange(url: String, range: HlsByteRange): ByteArray

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
 * that computes its segments from the clock starts at the newest complete segment, waits until each following segment
 * has elapsed, and runs to the scheduled stop. A live presentation that lists its segments (a SegmentList) starts at the
 * newest listed segment and requests each segment listed after it, re-reading the manifest at its minimum update
 * period. A live segment the origin has not published yet is retried within a bounded grace window. After that the
 * recording stops with the bytes it has, so a gap is never written silently. A byte range that arrives short stops the
 * recording the same way. Anything the planner refuses is reported as UNSUPPORTED before any byte is written.
 */
class DashCaptureEngine {
    enum class StopReason { END_LIST, CANCELLED, DEADLINE, LOW_STORAGE, UNSUPPORTED }

    data class Result(
        val bytesWritten: Long,
        val stopReason: StopReason,
        val detail: String? = null,
    )

    /** One request for a segment: its absolute address and, for a listed segment, its byte range. */
    private class Request(val url: String, val range: HlsByteRange?)

    private sealed class Initialization {
        class Written(val bytes: Long) : Initialization()
        class Stopped(val result: Result) : Initialization()
    }

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
            is DashPlanOutcome.NeedsIndex -> when (val completed = planWithIndex(outcome, loaded, source)) {
                is DashPlanOutcome.Unsupported -> return Result(0, StopReason.UNSUPPORTED, completed.reason)
                is DashPlanOutcome.Planned -> completed.plan
                is DashPlanOutcome.NeedsIndex -> return Result(0, StopReason.UNSUPPORTED, UNAVAILABLE_INDEX)
            }
        }
        val base = baseOf(plan, loaded) ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
        if (plan.timing is DashTiming.Live && plan.listedSegments != null) {
            return recordListedLive(
                initialUrl, plan, base, output, endEpochSeconds, source, cancelled, nowEpochSeconds, sleepMillis, onProgress,
            )
        }
        val timing = plan.timing
        // A static presentation ends at its last listed segment. A live one never does; the scheduled stop ends it.
        val lastNumber = plan.segmentCount?.let { plan.startNumber + it - 1 } ?: Long.MAX_VALUE

        var totalBytes = 0L
        when (val init = copyInitialization(plan, base, output, source, cancelled, onProgress)) {
            is Initialization.Stopped -> return init.result
            is Initialization.Written -> totalBytes = init.bytes
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
            val request = mediaRequest(plan, base, number) ?: return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_TOKEN)
            if (request.url.isEmpty()) return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
            val before = totalBytes
            val copy = try {
                source.copySegment(request.url, request.range, output, cancelled) { bytes ->
                    onProgress(progressBytes(before, bytes))
                }
            } catch (missing: DashSegmentNotPublishedException) {
                if (timing is DashTiming.Live && withinPublishGrace(timing, plan, number, nowEpochSeconds())) {
                    sleepMillis(LIVE_POLL_MILLIS)
                    continue
                }
                val reason = if (timing is DashTiming.Live) UNPUBLISHED_LIVE_SEGMENT else UNAVAILABLE_SEGMENT
                return Result(totalBytes, StopReason.UNSUPPORTED, reason)
            }
            totalBytes = addBytes(totalBytes, copy.bytesWritten)
                ?: return Result(totalBytes, StopReason.UNSUPPORTED, INVALID_BYTE_COUNT)
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
     * Records a live presentation that lists its segments: each pass requests every listed segment after the last one
     * fetched, starting at the live edge, and the manifest is then read again at its minimum update period. The
     * recording stops when the listing moves past the next segment, when it stops advancing or goes unanswered for the
     * stall limit, when the representation changes, when a listed segment stays unpublished through the grace window, or
     * at the scheduled stop. A timed listing (a live timeline) starts at its newest complete segment and fetches each
     * segment once it is complete. An open repeat after the listed entries continues the timed listing on the clock.
     */
    private fun recordListedLive(
        initialUrl: String,
        firstPlan: DashPlan,
        firstBase: String,
        output: OutputStream,
        endEpochSeconds: Long,
        source: DashCaptureSource,
        cancelled: () -> Boolean,
        nowEpochSeconds: () -> Long,
        sleepMillis: (Long) -> Unit,
        onProgress: (Long) -> Unit,
    ): Result {
        var plan = firstPlan
        var base = firstBase
        var totalBytes = 0L
        when (val init = copyInitialization(plan, base, output, source, cancelled, onProgress)) {
            is Initialization.Stopped -> return init.result
            is Initialization.Written -> totalBytes = init.bytes
        }
        // The identity that follows the last segment fetched; null until the first segment is fetched.
        var nextId: Long? = null
        var idleMillis = 0L
        while (true) {
            if (cancelled()) return Result(totalBytes, StopReason.CANCELLED)
            if (nowEpochSeconds() >= endEpochSeconds) return Result(totalBytes, StopReason.DEADLINE)
            val listed = plan.listedSegments ?: return Result(totalBytes, StopReason.UNSUPPORTED, LIVE_LISTING_MISSING)
            val startId = nextId
            var index = if (startId == null) {
                if (plan.listedTimed) lastCompletedIndex(plan, listed, nowEpochSeconds()) else listed.lastIndex
            } else {
                if (listed.first().id > startId) return Result(totalBytes, StopReason.UNSUPPORTED, LIVE_FELL_BEHIND)
                indexAtOrAfter(plan, listed, startId)
            }
            while (true) {
                // The listed segment at this index, or the next segment of an open tail. Past both, the pass ends.
                val entry = entryAt(plan, listed, index) ?: break
                if (cancelled()) return Result(totalBytes, StopReason.CANCELLED)
                if (nowEpochSeconds() >= endEpochSeconds) return Result(totalBytes, StopReason.DEADLINE)
                // Listed segments follow one another: the next one must start where the last one ended.
                if (nextId != null && entry.id != nextId) {
                    return Result(totalBytes, StopReason.UNSUPPORTED, LIVE_TIMELINE_MISALIGNED)
                }
                // A timed segment is fetched once it is complete. The listing is read again until then.
                if (plan.listedTimed && listedCompleteAt(plan, entry) > nowEpochSeconds().toDouble()) break
                val request = listedRequest(base, entry)
                if (request.url.isEmpty()) return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
                val before = totalBytes
                val copy = copyWhenPublished(source, request, output, cancelled, sleepMillis) { bytes ->
                    onProgress(progressBytes(before, bytes))
                } ?: return if (cancelled()) {
                    Result(totalBytes, StopReason.CANCELLED)
                } else {
                    Result(totalBytes, StopReason.UNSUPPORTED, UNPUBLISHED_LIVE_SEGMENT)
                }
                totalBytes = addBytes(totalBytes, copy.bytesWritten)
                    ?: return Result(totalBytes, StopReason.UNSUPPORTED, INVALID_BYTE_COUNT)
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
                nextId = entry.endId
                index++
            }
            sleepMillis(refreshMillisOf(plan))
            val reloaded = try {
                source.loadManifest(initialUrl)
            } catch (unanswered: Exception) {
                // A refresh the origin does not answer is retried at the next period. It counts as idle time, so a long
                // outage ends the recording with the bytes it has, and one dropped response does not end it.
                idleMillis += refreshMillisOf(plan)
                if (idleMillis >= STALL_LIMIT_MILLIS) return Result(totalBytes, StopReason.UNSUPPORTED, LIVE_MANIFEST_UNAVAILABLE)
                continue
            }
            val replanned = when (val outcome = DashManifest.plan(reloaded.text)) {
                is DashPlanOutcome.Unsupported -> return Result(totalBytes, StopReason.UNSUPPORTED, outcome.reason)
                is DashPlanOutcome.Planned -> outcome.plan
                // A live SegmentBase is refused by the planner, so a refresh that asks for an index has changed shape.
                is DashPlanOutcome.NeedsIndex -> return Result(totalBytes, StopReason.UNSUPPORTED, LIVE_CHANGED)
            }
            val replannedListing = replanned.listedSegments
            if (replanned.representationId != plan.representationId || replannedListing == null ||
                replanned.timing !is DashTiming.Live
            ) {
                return Result(totalBytes, StopReason.UNSUPPORTED, LIVE_CHANGED)
            }
            base = baseOf(replanned, reloaded) ?: return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
            plan = replanned
            // A refresh that brings no segment past the next one counts as idle time.
            val expected = nextId
            // An open tail advances with the clock, so its listing need not name a segment past the next one.
            val advanced = expected == null || replanned.openTail != null || replannedListing.any { it.id >= expected }
            idleMillis = if (advanced) 0L else idleMillis + refreshMillisOf(plan)
            if (idleMillis >= STALL_LIMIT_MILLIS) return Result(totalBytes, StopReason.UNSUPPORTED, LIVE_STALLED)
        }
    }

    /**
     * Reads a SegmentBase's index range, completes its plan, and refuses an index that does not cover its file. Nothing is
     * written before this succeeds.
     */
    private fun planWithIndex(
        request: DashPlanOutcome.NeedsIndex,
        loaded: LoadedDashManifest,
        source: DashCaptureSource,
    ): DashPlanOutcome {
        val fileUrl = baseOf(request.partial, loaded) ?: return DashPlanOutcome.Unsupported(UNSUPPORTED_RESOURCE)
        val bytes = try {
            source.loadRange(fileUrl, request.indexRange)
        } catch (unanswered: Exception) {
            return DashPlanOutcome.Unsupported(UNAVAILABLE_INDEX)
        }
        val completed = DashManifest.completeIndex(request, bytes)
        if (completed !is DashPlanOutcome.Planned) return completed
        val refusal = coverageRefusal(fileUrl, completed.plan, source) ?: return completed
        return DashPlanOutcome.Unsupported(refusal)
    }

    /**
     * An index covers its file when its last referenced byte exists and the byte after it does not. The file's length is
     * not known here, so each check reads one byte through [DashCaptureSource.loadRange]. Returns the refusal, or null.
     */
    private fun coverageRefusal(fileUrl: String, plan: DashPlan, source: DashCaptureSource): String? {
        val last = plan.listedSegments?.lastOrNull()?.range ?: return null
        val end = last.offset + last.length
        if (byteAt(fileUrl, end - 1, source) == null) return INDEX_PAST_END
        if (byteAt(fileUrl, end, source) != null) return UNCOVERED_INDEX
        return null
    }

    /**
     * The one byte at [offset], or null when the origin does not answer with it. The origin answers a byte past the end of
     * the file with an error, which reads as the byte being absent. A failed request reads the same way, so the core cannot
     * tell the two apart.
     */
    private fun byteAt(url: String, offset: Long, source: DashCaptureSource): ByteArray? = try {
        source.loadRange(url, HlsByteRange(length = 1, offset = offset)).takeIf { it.size == 1 }
    } catch (unanswered: Exception) {
        null
    }

    /** Copies the initialization segment, when the plan has one: a SegmentList's or a SegmentTemplate's. */
    private fun copyInitialization(
        plan: DashPlan,
        base: String,
        output: OutputStream,
        source: DashCaptureSource,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Initialization {
        val listedInitialization = plan.listedInitialization
        val initializationTemplate = plan.initializationTemplate
        val request = when {
            listedInitialization != null -> {
                val initialSource = listedInitialization.source
                val url = if (initialSource == null) base else resolveOrNull(base, initialSource)
                    ?: return Initialization.Stopped(Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE))
                Request(url, listedInitialization.range)
            }
            initializationTemplate != null -> {
                val expanded = DashManifest.expand(
                    initializationTemplate, plan.representationId, plan.bandwidth, plan.startNumber, plan.startTimeTicks,
                ) ?: return Initialization.Stopped(Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_TOKEN))
                val url = resolveOrNull(base, expanded)
                    ?: return Initialization.Stopped(Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE))
                Request(url, null)
            }
            else -> return Initialization.Written(0L)
        }
        val init = try {
            source.copySegment(request.url, request.range, output, cancelled) { bytes -> onProgress(bytes) }
        } catch (missing: DashSegmentNotPublishedException) {
            return Initialization.Stopped(Result(0, StopReason.UNSUPPORTED, UNAVAILABLE_INITIALIZATION))
        }
        when (init.stopReason) {
            StreamRecorder.StopReason.END_OF_STREAM -> {
                if (addBytes(0L, init.bytesWritten) == null) {
                    return Initialization.Stopped(Result(0, StopReason.UNSUPPORTED, INVALID_BYTE_COUNT))
                }
                if (init.bytesWritten == 0L) {
                    return Initialization.Stopped(Result(0, StopReason.UNSUPPORTED, "DASH initialization segment is empty"))
                }
                if (request.range != null && init.bytesWritten != request.range.length) {
                    return Initialization.Stopped(
                        Result(init.bytesWritten, StopReason.UNSUPPORTED, incompleteRange(request.range, init.bytesWritten)),
                    )
                }
                onProgress(init.bytesWritten)
            }
            StreamRecorder.StopReason.CANCELLED -> return Initialization.Stopped(Result(init.bytesWritten, StopReason.CANCELLED))
            StreamRecorder.StopReason.LOW_STORAGE -> return Initialization.Stopped(Result(init.bytesWritten, StopReason.LOW_STORAGE))
        }
        return Initialization.Written(init.bytesWritten)
    }

    /** Copies one listed segment, retrying it while the origin has not published it. Null when the grace window ends. */
    private fun copyWhenPublished(
        source: DashCaptureSource,
        request: Request,
        output: OutputStream,
        cancelled: () -> Boolean,
        sleepMillis: (Long) -> Unit,
        onProgress: (Long) -> Unit,
    ): StreamRecorder.Result? {
        var waited = 0L
        while (true) {
            try {
                return source.copySegment(request.url, request.range, output, cancelled, onProgress)
            } catch (missing: DashSegmentNotPublishedException) {
                if (waited >= LIVE_PUBLISH_GRACE_MILLIS || cancelled()) return null
                sleepMillis(LIVE_POLL_MILLIS)
                waited += LIVE_POLL_MILLIS
            }
        }
    }

    /** The base address: the manifest's BaseURL, then the representation's, each relative to the one before. */
    private fun baseOf(plan: DashPlan, loaded: LoadedDashManifest): String? {
        var base = loaded.effectiveUrl
        for (relative in listOfNotNull(plan.baseUrl, plan.representationBaseUrl)) {
            base = resolveOrNull(base, relative) ?: return null
        }
        return base
    }

    /** The request for a listed segment: its media URL (default the base), and its byte range. */
    private fun listedRequest(base: String, entry: DashListedSegment): Request {
        val url = if (entry.media == null) base else resolveOrNull(base, entry.media) ?: ""
        return Request(url, entry.range)
    }

    /**
     * The request for media segment [number]: a listed segment with its byte range, or a SegmentTemplate expansion.
     * Returns null when a template token cannot be expanded. A URL that cannot be resolved has an empty [Request.url].
     */
    private fun mediaRequest(plan: DashPlan, base: String, number: Long): Request? {
        val listed = plan.listedSegments
        if (listed != null) return listedRequest(base, listed[(number - plan.startNumber).toInt()])
        val expanded = DashManifest.expand(
            plan.mediaTemplate, plan.representationId, plan.bandwidth, number, timeTicks(plan, number),
        ) ?: return null
        return Request(resolveOrNull(base, expanded) ?: "", null)
    }

    /** A timed listed segment is complete at availabilityStart plus its end time, on the presentation clock. */
    private fun listedCompleteAt(plan: DashPlan, entry: DashListedSegment): Double {
        // Only a live presentation lists timed segments, so its timing is always live.
        val timing = plan.timing as DashTiming.Live
        return timing.availabilityStartEpochSeconds +
            (entry.endId - plan.presentationTimeOffsetTicks).toDouble() / plan.timescale.toDouble()
    }

    /**
     * The last listed segment that is complete at [nowEpochSeconds], or -1 when none is. An open tail continues the listing
     * on the clock, so its newest complete segment is found by arithmetic first, and the listed entries only when it has none.
     */
    private fun lastCompletedIndex(plan: DashPlan, listed: List<DashListedSegment>, nowEpochSeconds: Long): Int {
        val tail = plan.openTail
        if (tail != null) {
            val timing = plan.timing as DashTiming.Live
            // Presentation time elapsed so far, in timescale units. Tail segment k ends at tail start + (k + 1) * duration.
            val elapsedTicks = (nowEpochSeconds - timing.availabilityStartEpochSeconds) * plan.timescale +
                plan.presentationTimeOffsetTicks
            val newest = Math.floor((elapsedTicks - tail.startTicks) / tail.durationTicks.toDouble()).toLong() - 1
            if (newest >= 0) return listed.size + newest.toInt()
        }
        var index = listed.lastIndex
        while (index >= 0 && listedCompleteAt(plan, listed[index]) > nowEpochSeconds.toDouble()) index--
        return index
    }

    /** The index of the first segment that starts at or after [startId]: a listed one, or else one of the open tail. */
    private fun indexAtOrAfter(plan: DashPlan, listed: List<DashListedSegment>, startId: Long): Int {
        val found = listed.indexOfFirst { it.id >= startId }
        if (found >= 0) return found
        val tail = plan.openTail ?: return -1
        if (startId <= tail.startTicks) return listed.size
        val steps = (startId - tail.startTicks + tail.durationTicks - 1) / tail.durationTicks
        return listed.size + steps.toInt()
    }

    /** The listed segment at [index], or the open tail's segment there. Null when neither has one. */
    private fun entryAt(plan: DashPlan, listed: List<DashListedSegment>, index: Int): DashListedSegment? {
        if (index < 0) return null
        if (index < listed.size) return listed[index]
        val tail = plan.openTail ?: return null
        val start = Math.addExact(tail.startTicks, Math.multiplyExact((index - listed.size).toLong(), tail.durationTicks))
        val media = DashManifest.expand(
            tail.mediaTemplate, plan.representationId, plan.bandwidth, plan.startNumber + index, start,
        ) ?: return null
        return DashListedSegment(id = start, endId = start + tail.durationTicks, media = media, range = null)
    }

    /** How often a listed live manifest is read again, within bounds. */
    private fun refreshMillisOf(plan: DashPlan): Long {
        val seconds = (plan.timing as? DashTiming.Live)?.minimumUpdatePeriodSeconds
            ?: DashTiming.DEFAULT_MINIMUM_UPDATE_SECONDS
        return (seconds * 1000.0).toLong().coerceIn(MIN_REFRESH_MILLIS, MAX_REFRESH_MILLIS)
    }

    private fun incompleteRange(range: HlsByteRange, received: Long): String =
        "DASH byte range incomplete: expected ${range.length} bytes, received $received"

    /**
     * Adds a byte count reported by a source to the running total. A negative count or one that would overflow is
     * refused instead of escaping as an unchecked exception or wrapping into a negative total.
     */
    private fun addBytes(total: Long, bytes: Long): Long? =
        if (bytes < 0 || total > Long.MAX_VALUE - bytes) null else total + bytes

    /** Progress saturates rather than wrapping, even while a source is reporting an impossible byte count. */
    private fun progressBytes(before: Long, bytes: Long): Long = when {
        bytes <= 0L -> before
        before > Long.MAX_VALUE - bytes -> Long.MAX_VALUE
        else -> before + bytes
    }

    /** Start time of segment [number] in timescale units: the first segment's start plus whole durations. */
    private fun timeTicks(plan: DashPlan, number: Long): Long =
        Math.addExact(plan.startTimeTicks, Math.multiplyExact(number - plan.startNumber, plan.segmentDurationTicks))

    /**
     * The start of the first segment on the presentation timeline, which availability is measured on. Media time minus
     * the presentation offset: zero for a manifest that starts its media at the period start.
     */
    private fun presentationStartTicks(plan: DashPlan): Long = plan.startTimeTicks - plan.presentationTimeOffsetTicks

    /** The newest segment whose end time has passed at [nowEpochSeconds], measured from availabilityStart. */
    private fun newestCompleteNumber(timing: DashTiming.Live, plan: DashPlan, nowEpochSeconds: Long): Long {
        val elapsedTicks =
            (nowEpochSeconds - timing.availabilityStartEpochSeconds) * plan.timescale - presentationStartTicks(plan)
        return plan.startNumber + Math.floor(elapsedTicks / plan.segmentDurationTicks).toLong() - 1
    }

    /**
     * Segment [number] is complete when its presentation end time has passed:
     * availabilityStart + (media start - offset + duration) / timescale.
     */
    private fun completeAtEpochSeconds(timing: DashTiming.Live, plan: DashPlan, number: Long): Double =
        timing.availabilityStartEpochSeconds +
            (timeTicks(plan, number) - plan.presentationTimeOffsetTicks + plan.segmentDurationTicks).toDouble() /
            plan.timescale.toDouble()

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
        /** How long a clock-computed live segment may stay unpublished after its end time has passed. */
        const val LIVE_PUBLISH_GRACE_SECONDS = 10.0

        /** A listed live segment may stay unpublished for this long before the recording stops. */
        const val LIVE_PUBLISH_GRACE_MILLIS = 10_000L

        /** Live waits in half-second steps: prompt enough to stop on time, slow enough not to spin. */
        const val LIVE_POLL_MILLIS = 500L

        /** A listed live manifest is read again at its minimum update period, within these bounds. */
        const val MIN_REFRESH_MILLIS = 1_000L
        const val MAX_REFRESH_MILLIS = 10_000L

        /** A listed live presentation that brings no segment past the next one, or answers no manifest read, for this long stops. */
        const val STALL_LIMIT_MILLIS = 30_000L
        const val UNSUPPORTED_RESOURCE = "DASH resource must use HTTP or HTTPS"
        const val INVALID_BYTE_COUNT = "DASH byte count is invalid"
        const val UNSUPPORTED_TOKEN = "DASH template uses a token that is not supported"
        const val UNAVAILABLE_INITIALIZATION = "DASH initialization segment is not available"
        const val UNAVAILABLE_SEGMENT = "DASH segment is not available"
        const val UNAVAILABLE_INDEX = "DASH segment index is not available"
        const val INDEX_PAST_END = "DASH SegmentBase index names bytes past the end of the file"
        const val UNCOVERED_INDEX = "DASH SegmentBase index does not cover the file"
        const val UNPUBLISHED_LIVE_SEGMENT = "DASH live segment was not published in time"
        const val LIVE_LISTING_MISSING = "DASH live listing is missing"
        const val LIVE_FELL_BEHIND = "DASH live listing moved past the next segment"
        const val LIVE_STALLED = "DASH live listing stopped advancing"
        const val LIVE_MANIFEST_UNAVAILABLE = "DASH live manifest could not be read"
        const val LIVE_TIMELINE_MISALIGNED = "DASH live timeline does not continue the recorded segments"
        const val LIVE_CHANGED = "DASH live manifest changed during recording"
    }
}

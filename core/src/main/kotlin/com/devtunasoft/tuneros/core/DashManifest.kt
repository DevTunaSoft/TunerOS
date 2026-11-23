package com.devtunasoft.tuneros.core

import java.io.StringReader
import java.time.Duration
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** What a DASH recording needs from a manifest. Only the fields the recorder reads. */
data class DashPlan(
    /** The manifest's own BaseURL, resolved against the manifest address. */
    val baseUrl: String?,
    val representationId: String,
    val bandwidth: Long,
    val initializationTemplate: String?,
    val mediaTemplate: String,
    /** The number of the first segment. Numbers count from here, with or without a SegmentTimeline. */
    val startNumber: Long,
    val timescale: Long,
    /** Start time of the segment numbered [startNumber], in timescale units. Zero without a SegmentTimeline. */
    val startTimeTicks: Long,
    /** Segment duration in timescale units. */
    val segmentDurationTicks: Long,
    /** Static presentations only: the number of media segments. Null for live, which the scheduled stop ends. */
    val segmentCount: Long?,
    val timing: DashTiming,
    /** The representation's own BaseURL, resolved after [baseUrl]. */
    val representationBaseUrl: String? = null,
    /** A SegmentList's initialization: its source and byte range. Null when the initialization comes from a template. */
    val listedInitialization: DashListedInitialization? = null,
    /** Listed addressing: the segments in order, from a SegmentList or a SegmentTimeline. Null for a plain template. */
    val listedSegments: List<DashListedSegment>? = null,
    /** Media time minus presentation time, from the SegmentTemplate. Zero when the manifest does not set one. */
    val presentationTimeOffsetTicks: Long = 0L,
    /**
     * True when the listed segments are timeline segments of a live presentation: each is fetched once its end time has
     * passed on the presentation clock. False for a SegmentList, whose segments are fetched as soon as they are listed.
     */
    val listedTimed: Boolean = false,
    /**
     * A live listing that ends in an open repeat: the segments that follow [listedSegments], one per duration of the
     * open tail, on the presentation clock. Null when the listing is finite.
     */
    val openTail: DashOpenTail? = null,
) {
    val segmentDurationSeconds: Double
        get() = segmentDurationTicks.toDouble() / timescale.toDouble()
}

/** A SegmentList's initialization: a source (default the base URL) and an optional byte range. */
data class DashListedInitialization(val source: String?, val range: HlsByteRange?)

/**
 * One listed segment: its identity in the listing (a SegmentList number, or the start time of a timeline segment), the
 * identity that follows it, a media URL (default the base URL) and an optional byte range. Identities increase along
 * the listing, and each segment starts where the previous one ends.
 */
data class DashListedSegment(val id: Long, val endId: Long, val media: String?, val range: HlsByteRange?)

/** The open tail of a live listing: its first start time, its one duration, and the media template its segments expand. */
data class DashOpenTail(val startTicks: Long, val durationTicks: Long, val mediaTemplate: String)

/** How segment numbers relate to time. */
sealed class DashTiming {
    /** A finished presentation. Its media segments are counted from the presentation duration or the timeline. */
    data class Static(val mediaPresentationDurationSeconds: Double) : DashTiming()

    /**
     * A live presentation. For a SegmentTemplate with a fixed duration or an open timeline, segment N is complete at
     * availabilityStart + (start + N - startNumber + 1) times duration, so the recorder follows the clock and requests
     * each segment only after its duration has elapsed. A live SegmentList or a listed timeline is followed through its
     * listing instead. availabilityTimeOffset and availabilityTimeComplete are not read, and a truncated body is refused
     * by the length check on the copy.
     */
    data class Live(
        val availabilityStartEpochSeconds: Double,
        /** How often a listed live manifest is read again. */
        val minimumUpdatePeriodSeconds: Double = DEFAULT_MINIMUM_UPDATE_SECONDS,
    ) : DashTiming()

    companion object {
        const val DEFAULT_MINIMUM_UPDATE_SECONDS = 2.0
    }
}

sealed class DashPlanOutcome {
    data class Planned(val plan: DashPlan) : DashPlanOutcome()
    data class Unsupported(val reason: String) : DashPlanOutcome()

    /**
     * A SegmentBase whose fragments the index locates. Read the bytes of [indexRange], then complete [partial] with
     * [DashManifest.completeIndex].
     */
    data class NeedsIndex(val indexRange: HlsByteRange, val partial: DashPlan) : DashPlanOutcome()
}

/**
 * Bounded DASH planner for static and live manifests. It accepts one period with one video track, muxed or
 * video-only. Its representation is addressed by a SegmentTemplate, with `$Number$` or, with a SegmentTimeline,
 * `$Time$`; or by a SegmentList of URLs and byte ranges, or by a static SegmentBase whose sidx index locates each
 * fragment of one file. A timeline must be contiguous: its entries follow one another,
 * and their durations may differ. A static manifest carries a presentation duration or a timeline or list, and the
 * recorder counts its segments from it. A static timeline of one duration is read arithmetically; any other timeline is
 * listed segment by segment. A live manifest carries an availabilityStartTime with an offset. A live timeline that
 * repeats to the end is followed on that clock at the duration of its open repeat, after any entries listed before it.
 * Every other live timeline, and a live SegmentList, is listed and read again at its minimum update period, and each
 * listed segment is fetched once it is complete (a SegmentList's are fetched as they are listed). It refuses everything
 * else with a fixed reason: separate audio or text renditions, protection, a live SegmentBase, timeline gaps, an open repeat in a static
 * timeline or one that is not a timeline's last entry, DTDs and unknown template tokens. No manifest text, URL or exception
 * message is kept.
 */
object DashManifest {
    const val MAX_MANIFEST_CHARS = 1_000_000
    private const val MAX_TEMPLATE_CHARS = 512
    private const val MAX_TIMELINE_ENTRIES = 1_000
    private const val MAX_TIMELINE_TICKS = 1_000_000_000_000L
    private const val MAX_LIST_SEGMENTS = 10_000
    private const val MAX_RANGE_BYTE = 1_000_000_000_000_000L
    private const val MAX_SEGMENTS = 100_000L

    /** Counts whole segments, so a duration a few milliseconds past a segment boundary adds no segment. */
    private const val COUNT_TOLERANCE = 0.05

    fun plan(text: String): DashPlanOutcome {
        if (text.length > MAX_MANIFEST_CHARS) return refuse("DASH manifest is too large")
        // A DTD can declare entities. Refuse it before any parser sees the text.
        if (text.contains("<!DOCTYPE", ignoreCase = true)) return refuse("DASH manifest declares a DTD, which is not accepted")
        val root = parse(text) ?: return refuse("DASH manifest is not well formed")
        if (root.nodeName != "MPD") return refuse("Not a DASH manifest")
        val live = when (root.attr("type")) {
            null, "static" -> false
            "dynamic" -> true
            else -> return refuse("Unknown DASH manifest type")
        }
        for (name in UNSUPPORTED_ELEMENTS) {
            if (root.getElementsByTagName(name).length > 0) return refuse("DASH $name is not supported")
        }
        // A BaseURL may sit on the MPD or on a Representation, and at most once on each.
        val baseUrls = root.getElementsByTagName("BaseURL")
        for (index in 0 until baseUrls.length) {
            val parent = baseUrls.item(index).parentNode.nodeName
            if (parent != "MPD" && parent != "Representation") {
                return refuse("Nested or repeated DASH BaseURL is not supported")
            }
        }
        val topBaseUrls = root.childElements("BaseURL")
        if (topBaseUrls.size > 1) return refuse("Nested or repeated DASH BaseURL is not supported")
        val timing: DashTiming = if (live) {
            val start = root.attr("availabilityStartTime")?.let { epochSeconds(it) }
                ?: return refuse("Live DASH manifest needs a valid availabilityStartTime")
            val updatePeriod = root.attr("minimumUpdatePeriod")?.let { seconds(it) }?.takeIf { it > 0 }
            DashTiming.Live(start, updatePeriod ?: DashTiming.DEFAULT_MINIMUM_UPDATE_SECONDS)
        } else {
            val duration = root.attr("mediaPresentationDuration")?.let { seconds(it) }
                ?: return refuse("Static DASH manifest needs a presentation duration")
            DashTiming.Static(duration)
        }
        val periods = root.childElements("Period")
        if (periods.size != 1) return refuse("DASH manifests with exactly one period are supported")
        periods[0].attr("start")?.let { start ->
            if (seconds(start) != 0.0) return refuse("DASH period start offsets are not supported")
        }

        val videoSets = mutableListOf<Element>()
        for (set in periods[0].childElements("AdaptationSet")) {
            val contentType = set.attr("contentType")
            val mimeType = set.attr("mimeType") ?: set.childElements("Representation").firstOrNull()?.attr("mimeType")
            val isVideo = contentType == "video" || mimeType?.startsWith("video/") == true
            if (!isVideo) return refuse("Separate audio or text renditions are not supported")
            videoSets += set
        }
        if (videoSets.size != 1) return refuse("DASH manifests with exactly one video track are supported")

        val set = videoSets[0]
        val setTemplate = set.childElements("SegmentTemplate").firstOrNull()
        val setList = set.childElements("SegmentList").firstOrNull()
        val setBase = set.childElements("SegmentBase").firstOrNull()
        var chosen: Element? = null
        var chosenAddressing: Element? = null
        var chosenBandwidth = -1L
        for (representation in set.childElements("Representation")) {
            // A representation's own addressing wins over the AdaptationSet's, and a template over a list.
            val addressing = representation.childElements("SegmentTemplate").firstOrNull()
                ?: representation.childElements("SegmentList").firstOrNull()
                ?: representation.childElements("SegmentBase").firstOrNull()
                ?: setTemplate ?: setList ?: setBase ?: continue
            val bandwidth = representation.attr("bandwidth")?.toLongOrNull() ?: 0L
            if (bandwidth > chosenBandwidth) {
                chosen = representation
                chosenAddressing = addressing
                chosenBandwidth = bandwidth
            }
        }
        val representation = chosen
            ?: return refuse("No DASH representation has a SegmentTemplate or SegmentList")
        val addressing = chosenAddressing
            ?: return refuse("No DASH representation has a SegmentTemplate or SegmentList")
        if (representation.childElements("BaseURL").size > 1) return refuse("Nested or repeated DASH BaseURL is not supported")
        val id = representation.attr("id") ?: return refuse("DASH representation has no id")
        if (addressing.nodeName == "SegmentList") {
            return planList(live, timing, topBaseUrls, representation, id, chosenBandwidth, addressing)
        }
        if (addressing.nodeName == "SegmentBase") {
            return planSegmentBase(live, timing, topBaseUrls, representation, id, chosenBandwidth, addressing)
        }
        val template = addressing
        val media = template.attr("media") ?: return refuse("DASH SegmentTemplate has no media template")
        val initialization = template.attr("initialization")
        val timescale = template.attr("timescale")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 } ?: return refuse("DASH timescale is not a positive number")
        } ?: 1L
        val timeline = when (val parsed = timelineOf(template, live)) {
            null -> null
            is TimelineOutcome.Refused -> return refuse(parsed.reason)
            is TimelineOutcome.Regular -> parsed.timeline
            is TimelineOutcome.Listed -> return planTimeline(
                live, timing, topBaseUrls, representation, id, chosenBandwidth, template, media, initialization, timescale,
                parsed.segments, tail = null,
            )
            is TimelineOutcome.Open -> return planTimeline(
                live, timing, topBaseUrls, representation, id, chosenBandwidth, template, media, initialization, timescale,
                parsed.segments, tail = parsed.tail,
            )
        }
        val durationTicks = timeline?.durationTicks
            ?: template.attr("duration")?.toLongOrNull()?.takeIf { it > 0 }
            ?: return refuse("DASH SegmentTemplate has no positive duration")
        val startNumber = template.attr("startNumber")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: return refuse("DASH startNumber is not a valid number")
        } ?: 1L
        // The offset maps media time to presentation time. Without a timeline, the first segment's media time is the
        // offset itself, so its presentation time is zero.
        val presentationTimeOffset = template.attr("presentationTimeOffset")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: return refuse("DASH presentationTimeOffset is not valid")
        } ?: 0L
        val startTicks = timeline?.startTicks ?: presentationTimeOffset
        // $Time$ names a segment by its start time, which only a SegmentTimeline provides.
        val firstTime = if (timeline != null) startTicks else null
        if (expand(media, id, bandwidth = chosenBandwidth, number = startNumber, timeTicks = firstTime) == null) {
            return refuse("DASH media template uses a token that is not supported")
        }
        if (initialization != null &&
            expand(initialization, id, bandwidth = chosenBandwidth, number = startNumber, timeTicks = firstTime) == null
        ) {
            return refuse("DASH initialization template uses a token that is not supported")
        }
        val segmentCount: Long? = when (timing) {
            is DashTiming.Live -> null
            is DashTiming.Static -> timeline?.listedSegments ?: run {
                val count = Math.ceil(
                    timing.mediaPresentationDurationSeconds /
                        (durationTicks.toDouble() / timescale.toDouble()) - COUNT_TOLERANCE,
                ).toLong()
                if (count < 1) return refuse("DASH manifest has no media segments")
                if (count > MAX_SEGMENTS) return refuse("DASH manifest has too many segments")
                // The recorder fetches through startNumber + count - 1. A wrapped last number would silently
                // truncate the recording instead of refusing an unrepresentable range.
                if (runCatching { Math.addExact(startNumber, count - 1) }.getOrNull() == null) {
                    return refuse("DASH segment numbers overflow")
                }
                count
            }
        }
        return DashPlanOutcome.Planned(
            DashPlan(
                baseUrl = topBaseUrls.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                representationId = id,
                bandwidth = chosenBandwidth,
                initializationTemplate = initialization,
                mediaTemplate = media,
                startNumber = startNumber,
                timescale = timescale,
                startTimeTicks = startTicks,
                segmentDurationTicks = durationTicks,
                segmentCount = segmentCount,
                timing = timing,
                representationBaseUrl = representationBaseUrl(representation),
                presentationTimeOffsetTicks = presentationTimeOffset,
            ),
        )
    }

    /**
     * A SegmentList: the listed URLs and byte ranges, in order. Segment identity is startNumber plus position, so a live
     * list must advance startNumber as it slides.
     */
    private fun planList(
        live: Boolean,
        timing: DashTiming,
        topBaseUrls: List<Element>,
        representation: Element,
        id: String,
        bandwidth: Long,
        list: Element,
    ): DashPlanOutcome {
        val timescale = list.attr("timescale")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 } ?: return refuse("DASH timescale is not a positive number")
        } ?: 1L
        val durationTicks = list.attr("duration")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val startNumber = list.attr("startNumber")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: return refuse("DASH startNumber is not a valid number")
        } ?: 1L
        val initElement = list.childElements("Initialization").firstOrNull()
        val initialization = if (initElement == null) {
            null
        } else {
            DashListedInitialization(
                source = initElement.attr("sourceURL"),
                range = initElement.attr("range")?.let { parseRange(it) ?: return refuse("DASH byte range is not valid") },
            )
        }
        val entries = list.childElements("SegmentURL")
        if (entries.isEmpty()) return refuse("DASH SegmentList has no segments")
        if (entries.size > MAX_LIST_SEGMENTS) return refuse("DASH SegmentList has too many segments")
        val hasBase = topBaseUrls.isNotEmpty() || representationBaseUrl(representation) != null
        val segments = ArrayList<DashListedSegment>(entries.size)
        for ((index, entry) in entries.withIndex()) {
            val media = entry.attr("media")
            val range = entry.attr("mediaRange")?.let { parseRange(it) ?: return refuse("DASH SegmentURL byte range is not valid") }
            if (media == null && range == null) return refuse("DASH SegmentURL has no media URL or byte range")
            if (media == null && !hasBase) return refuse("DASH SegmentURL has no media URL and no BaseURL")
            val number = startNumber + index
            segments += DashListedSegment(id = number, endId = number + 1, media = media, range = range)
        }
        return DashPlanOutcome.Planned(
            DashPlan(
                baseUrl = topBaseUrls.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                representationId = id,
                bandwidth = bandwidth,
                initializationTemplate = null,
                mediaTemplate = "",
                startNumber = startNumber,
                timescale = timescale,
                startTimeTicks = 0L,
                segmentDurationTicks = durationTicks,
                segmentCount = if (live) null else segments.size.toLong(),
                timing = timing,
                representationBaseUrl = representationBaseUrl(representation),
                listedInitialization = initialization,
                listedSegments = segments,
            ),
        )
    }

    /**
     * A listed SegmentTimeline: contiguous segments addressed by their start times, listed one by one. A live window is
     * read again at the minimum update period, and each listed segment is fetched once its end time has passed. Numbers
     * follow the window's startNumber and position, as a SegmentList's do.
     */
    private fun planTimeline(
        live: Boolean,
        timing: DashTiming,
        topBaseUrls: List<Element>,
        representation: Element,
        id: String,
        bandwidth: Long,
        template: Element,
        media: String,
        initialization: String?,
        timescale: Long,
        segments: List<TimelineSegment>,
        tail: TimelineSegment?,
    ): DashPlanOutcome {
        val startNumber = template.attr("startNumber")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: return refuse("DASH startNumber is not a valid number")
        } ?: 1L
        val presentationTimeOffset = template.attr("presentationTimeOffset")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: return refuse("DASH presentationTimeOffset is not valid")
        } ?: 0L
        val first = segments.first()
        if (expand(media, id, bandwidth, startNumber, first.startTicks) == null) {
            return refuse("DASH media template uses a token that is not supported")
        }
        if (initialization != null && expand(initialization, id, bandwidth, startNumber, first.startTicks) == null) {
            return refuse("DASH initialization template uses a token that is not supported")
        }
        val listed = segments.mapIndexed { index, segment ->
            val expanded = expand(media, id, bandwidth, startNumber + index, segment.startTicks)
                ?: return refuse("DASH media template uses a token that is not supported")
            DashListedSegment(
                id = segment.startTicks,
                endId = segment.startTicks + segment.durationTicks,
                media = expanded,
                range = null,
            )
        }
        // The open tail continues the listing, so its first segment takes the number after the listed ones.
        val openTail = tail?.let { tailSegment ->
            if (expand(media, id, bandwidth, startNumber + listed.size, tailSegment.startTicks) == null) {
                return refuse("DASH media template uses a token that is not supported")
            }
            DashOpenTail(startTicks = tailSegment.startTicks, durationTicks = tailSegment.durationTicks, mediaTemplate = media)
        }
        return DashPlanOutcome.Planned(
            DashPlan(
                baseUrl = topBaseUrls.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                representationId = id,
                bandwidth = bandwidth,
                initializationTemplate = initialization,
                mediaTemplate = "",
                startNumber = startNumber,
                timescale = timescale,
                startTimeTicks = first.startTicks,
                segmentDurationTicks = first.durationTicks,
                segmentCount = if (live) null else listed.size.toLong(),
                timing = timing,
                representationBaseUrl = representationBaseUrl(representation),
                listedSegments = listed,
                presentationTimeOffsetTicks = presentationTimeOffset,
                listedTimed = live,
                openTail = openTail,
            ),
        )
    }

    /**
     * A SegmentBase: one media file whose segment index, a sidx box, is named by indexRange and locates each fragment.
     * The index must be read first, so this returns the request for it, and [completeIndex] finishes the plan. A live
     * SegmentBase is refused, since its index would have to be read again on every refresh.
     */
    private fun planSegmentBase(
        live: Boolean,
        timing: DashTiming,
        topBaseUrls: List<Element>,
        representation: Element,
        id: String,
        bandwidth: Long,
        base: Element,
    ): DashPlanOutcome {
        if (live) return refuse("DASH live SegmentBase is not supported")
        val indexRange = base.attr("indexRange")?.let { parseRange(it) }
            ?: return refuse("DASH SegmentBase needs a valid indexRange")
        if (indexRange.length > DashSegmentIndex.MAX_INDEX_BYTES) return refuse("DASH SegmentBase index is too large")
        val initialization = base.childElements("Initialization").firstOrNull()
            ?: return refuse("DASH SegmentBase needs an Initialization range")
        if (initialization.attr("sourceURL") != null) return refuse("DASH SegmentBase initialization must be in the media file")
        val initRange = initialization.attr("range")?.let { parseRange(it) }
            ?: return refuse("DASH SegmentBase needs an Initialization range")
        val mediaBase = topBaseUrls.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val representationBase = representationBaseUrl(representation)
        if (mediaBase == null && representationBase == null) {
            return refuse("DASH SegmentBase needs a BaseURL for its media file")
        }
        return DashPlanOutcome.NeedsIndex(
            indexRange = indexRange,
            partial = DashPlan(
                baseUrl = mediaBase,
                representationId = id,
                bandwidth = bandwidth,
                initializationTemplate = null,
                mediaTemplate = "",
                startNumber = 1L,
                timescale = 1L,
                startTimeTicks = 0L,
                segmentDurationTicks = 1L,
                segmentCount = null,
                timing = timing,
                representationBaseUrl = representationBase,
                listedInitialization = DashListedInitialization(source = null, range = initRange),
                listedSegments = null,
            ),
        )
    }

    /**
     * Completes a SegmentBase plan from the bytes of its index range. Each media fragment becomes a listed byte range of
     * the one file, in order, and the initialization range comes from the request.
     */
    fun completeIndex(request: DashPlanOutcome.NeedsIndex, bytes: ByteArray): DashPlanOutcome =
        when (val index = DashSegmentIndex.parse(bytes, request.indexRange)) {
            is DashSegmentIndex.Outcome.Refused -> refuse(index.reason)
            is DashSegmentIndex.Outcome.Parsed -> {
                val listed = index.references.mapIndexed { position, reference ->
                    val number = 1L + position
                    DashListedSegment(id = number, endId = number + 1, media = null, range = reference.range)
                }
                DashPlanOutcome.Planned(
                    request.partial.copy(
                        timescale = index.timescale,
                        segmentDurationTicks = index.references[0].durationTicks,
                        segmentCount = listed.size.toLong(),
                        listedSegments = listed,
                    ),
                )
            }
        }

    private fun representationBaseUrl(representation: Element): String? =
        representation.childElements("BaseURL").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** Parses "first-last", both inclusive, into a byte range. Null for anything else. */
    private fun parseRange(text: String): HlsByteRange? {
        val parts = text.trim().split('-')
        if (parts.size != 2) return null
        val first = parts[0].toLongOrNull() ?: return null
        val last = parts[1].toLongOrNull() ?: return null
        if (first < 0 || last < first || last > MAX_RANGE_BYTE) return null
        return HlsByteRange(length = last - first + 1, offset = first)
    }

    /**
     * Expands `$RepresentationID$`, `$Bandwidth$`, `$Number$`, `$Number%0Nd$` (N from 1 to 9) and, when [timeTicks]
     * is given by a SegmentTimeline, `$Time$`, with `$$` as a literal dollar sign. Any other token returns null, so a
     * template the recorder cannot honour is refused.
     */
    fun expand(
        template: String,
        representationId: String,
        bandwidth: Long,
        number: Long,
        timeTicks: Long? = null,
    ): String? {
        if (template.length > MAX_TEMPLATE_CHARS) return null
        val out = StringBuilder(template.length + 16)
        var index = 0
        while (index < template.length) {
            val ch = template[index]
            if (ch != '$') {
                out.append(ch)
                index++
                continue
            }
            val end = template.indexOf('$', index + 1)
            if (end < 0) return null
            val token = template.substring(index + 1, end)
            when {
                token.isEmpty() -> out.append('$')
                token == "RepresentationID" -> out.append(representationId)
                token == "Bandwidth" -> out.append(bandwidth)
                token == "Number" -> out.append(number)
                token == "Time" -> out.append(timeTicks ?: return null)
                token.startsWith("Number%0") && token.endsWith("d") -> {
                    val width = token.substring("Number%0".length, token.length - 1).toIntOrNull() ?: return null
                    if (width !in 1..9) return null
                    out.append(number.toString().padStart(width, '0'))
                }
                else -> return null
            }
            index = end + 1
        }
        return out.toString()
    }

    /** A SegmentTimeline read as an arithmetic sequence: one duration, with entries one after another. */
    private class Timeline(val startTicks: Long, val durationTicks: Long, val listedSegments: Long)

    /** One segment of a listed SegmentTimeline: its start time and its duration, in timescale units. */
    private class TimelineSegment(val startTicks: Long, val durationTicks: Long)

    /** One SegmentTimeline entry as read: its duration, its repeat count, and the start time of its first segment. */
    private class TimelineEntry(val durationTicks: Long, val repeat: Int, val startTicks: Long)

    private sealed class TimelineOutcome {
        /** A timeline of one duration: read arithmetically when static, or on the clock when live and open-ended. */
        class Regular(val timeline: Timeline) : TimelineOutcome()
        /** Segments listed one by one: a static timeline of mixed durations, or any live window a refresh re-reads. */
        class Listed(val segments: List<TimelineSegment>) : TimelineOutcome()
        /**
         * A live timeline whose entries before its open repeat are listed one by one. The open repeat then continues the
         * listing on the clock at its own duration, from the start time [tail] names.
         */
        class Open(val segments: List<TimelineSegment>, val tail: TimelineSegment) : TimelineOutcome()
        class Refused(val reason: String) : TimelineOutcome()
    }

    /**
     * Reads the SegmentTimeline of [template], or returns null when it has none. A live manifest may end its timeline
     * with an open repeat (r="-1"), which runs to the clock; any other entry must be listed. Entries must follow one
     * another: a gap is refused, since the recorder would otherwise skip media silently. A timeline of one duration is
     * read arithmetically when static, and followed on the clock when live and open-ended. Every other timeline is
     * listed segment by segment, within the segment limit. An irregular live timeline that repeats to the end lists the
     * entries before the repeat, and the repeat follows the clock.
     */
    private fun timelineOf(template: Element, live: Boolean): TimelineOutcome? {
        val timelines = template.childElements("SegmentTimeline")
        if (timelines.isEmpty()) return null
        if (timelines.size != 1) return TimelineOutcome.Refused("DASH SegmentTemplate has more than one SegmentTimeline")
        val elements = timelines[0].childElements("S")
        if (elements.isEmpty()) return TimelineOutcome.Refused("DASH SegmentTimeline has no entries")
        if (elements.size > MAX_TIMELINE_ENTRIES) return TimelineOutcome.Refused("DASH SegmentTimeline has too many entries")
        val entries = ArrayList<TimelineEntry>(elements.size)
        var expectedStart = 0L
        var listed = 0L
        var openRepeat = false
        try {
            for ((index, element) in elements.withIndex()) {
                val d = element.attr("d")?.toLongOrNull()?.takeIf { it in 1L..MAX_TIMELINE_TICKS }
                    ?: return TimelineOutcome.Refused("DASH SegmentTimeline entry needs a positive duration")
                val repeat = element.attr("r")?.let { raw ->
                    raw.toIntOrNull() ?: return TimelineOutcome.Refused("DASH SegmentTimeline repeat count is not a number")
                } ?: 0
                if (repeat < -1) return TimelineOutcome.Refused("DASH SegmentTimeline repeat count is not valid")
                val t = element.attr("t")?.let { raw ->
                    raw.toLongOrNull()?.takeIf { it >= 0 }
                        ?: return TimelineOutcome.Refused("DASH SegmentTimeline start time is not valid")
                }
                if (index == 0) {
                    expectedStart = t ?: 0L
                } else if (t != null && t != expectedStart) {
                    return TimelineOutcome.Refused("DASH SegmentTimeline is not contiguous; gaps are not supported")
                }
                entries += TimelineEntry(d, repeat, expectedStart)
                if (repeat == -1) {
                    // An open repeat runs to the clock, which only a live presentation has.
                    if (index != elements.lastIndex) {
                        return TimelineOutcome.Refused("DASH SegmentTimeline repeats to the end only in its last entry")
                    }
                    if (!live) return TimelineOutcome.Refused("DASH SegmentTimeline repeats to the end only in live manifests")
                    openRepeat = true
                    break
                }
                listed = Math.addExact(listed, repeat + 1L)
                if (listed > MAX_SEGMENTS) return TimelineOutcome.Refused("DASH manifest has too many segments")
                expectedStart = Math.addExact(expectedStart, Math.multiplyExact(repeat + 1L, d))
            }
        } catch (overflow: ArithmeticException) {
            return TimelineOutcome.Refused("DASH SegmentTimeline is too long")
        }
        val first = entries[0]
        val regular = entries.all { it.durationTicks == first.durationTicks }
        if (openRepeat) {
            // Only a live presentation repeats to the end. A regular timeline is followed on the clock, which needs one
            // duration. An irregular one lists the entries before its open repeat, and the repeat follows the clock.
            if (regular) return TimelineOutcome.Regular(Timeline(first.startTicks, first.durationTicks, listed))
            if (listed > MAX_LIST_SEGMENTS) return TimelineOutcome.Refused("DASH SegmentTimeline has too many segments to list")
            val repeated = entries.last()
            return TimelineOutcome.Open(
                listEntries(entries.dropLast(1), listed),
                TimelineSegment(startTicks = repeated.startTicks, durationTicks = repeated.durationTicks),
            )
        }
        if (!live && regular) return TimelineOutcome.Regular(Timeline(first.startTicks, first.durationTicks, listed))
        // A static timeline of mixed durations, or a live window a refresh re-reads: each segment is listed by its start.
        if (listed > MAX_LIST_SEGMENTS) return TimelineOutcome.Refused("DASH SegmentTimeline has too many segments to list")
        return TimelineOutcome.Listed(listEntries(entries, listed))
    }

    /** Lists the segments of [entries] in order, each at its start time. [count] is the number of segments listed. */
    private fun listEntries(entries: List<TimelineEntry>, count: Long): List<TimelineSegment> {
        val segments = ArrayList<TimelineSegment>(count.toInt())
        for (entry in entries) {
            for (k in 0..entry.repeat) {
                segments += TimelineSegment(
                    startTicks = entry.startTicks + k.toLong() * entry.durationTicks,
                    durationTicks = entry.durationTicks,
                )
            }
        }
        return segments
    }

    private val UNSUPPORTED_ELEMENTS = listOf("ContentProtection")

    private fun refuse(reason: String): DashPlanOutcome = DashPlanOutcome.Unsupported(reason)

    private fun seconds(value: String): Double? {
        val parsed = runCatching { Duration.parse(value) }.getOrNull() ?: return null
        if (parsed.isNegative) return null
        return parsed.toMillis() / 1000.0
    }

    /** Epoch seconds of an xs:dateTime that carries an offset. A time without one is ambiguous, so it is refused. */
    private fun epochSeconds(value: String): Double? {
        val parsed = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() ?: return null
        return parsed.epochSecond + parsed.nano / 1_000_000_000.0
    }

    /** Parses without DTD or entity expansion. Returns null for anything that is not well formed XML. */
    private fun parse(text: String): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
    }.getOrNull()

    private fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

    private fun Element.childElements(name: String): List<Element> {
        val result = ArrayList<Element>()
        val children = childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) result += node as Element
        }
        return result
    }
}

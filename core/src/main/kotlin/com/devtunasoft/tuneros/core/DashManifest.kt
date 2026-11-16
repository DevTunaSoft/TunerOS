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
    /** Static presentations only: the number of media segments. Null for live, which the clock ends. */
    val segmentCount: Long?,
    val timing: DashTiming,
    /** The representation's own BaseURL, resolved after [baseUrl]. */
    val representationBaseUrl: String? = null,
    /** SegmentList addressing: the initialization, and the listed segments in order. Null for a SegmentTemplate. */
    val listedInitialization: DashListedInitialization? = null,
    val listedSegments: List<DashListedSegment>? = null,
) {
    val segmentDurationSeconds: Double
        get() = segmentDurationTicks.toDouble() / timescale.toDouble()
}

/** A SegmentList's initialization: a source (default the base URL) and an optional byte range. */
data class DashListedInitialization(val source: String?, val range: HlsByteRange?)

/** One SegmentList entry: a media URL (default the base URL) and an optional byte range. */
data class DashListedSegment(val media: String?, val range: HlsByteRange?)

/** How segment numbers relate to time. */
sealed class DashTiming {
    /** A finished presentation. Its media segments are counted from the presentation duration or the timeline. */
    data class Static(val mediaPresentationDurationSeconds: Double) : DashTiming()

    /**
     * A live presentation. Segment N is complete at availabilityStart + (start + N - startNumber + 1) * duration, so
     * the recorder follows the clock rather than a segment list. availabilityTimeOffset and availabilityTimeComplete
     * are not read: each segment is requested only after its full duration has elapsed, and a truncated body is
     * refused by the length check on the copy.
     */
    data class Live(val availabilityStartEpochSeconds: Double) : DashTiming()
}

sealed class DashPlanOutcome {
    data class Planned(val plan: DashPlan) : DashPlanOutcome()
    data class Unsupported(val reason: String) : DashPlanOutcome()
}

/**
 * Bounded DASH planner for static and live manifests. It accepts one period with one video track, muxed or
 * video-only. Its representation is addressed by a SegmentTemplate, with `$Number$` or, with a SegmentTimeline,
 * `$Time$`; or by a static SegmentList of URLs and byte ranges. A timeline must be regular: its entries share one
 * duration and follow one another. A static manifest carries a presentation duration or a timeline or list, and the
 * recorder counts its segments from it. A live manifest carries an availabilityStartTime with an offset, and the
 * recorder follows that clock; a live SegmentList needs a manifest refresh and is refused. It refuses everything else
 * with a fixed reason: separate audio or text renditions, protection, SegmentBase, irregular timelines, DTDs and
 * unknown template tokens. No manifest text, URL or exception message is kept.
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
            DashTiming.Live(start)
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
        var chosen: Element? = null
        var chosenAddressing: Element? = null
        var chosenBandwidth = -1L
        for (representation in set.childElements("Representation")) {
            // A representation's own addressing wins over the AdaptationSet's, and a template over a list.
            val addressing = representation.childElements("SegmentTemplate").firstOrNull()
                ?: representation.childElements("SegmentList").firstOrNull()
                ?: setTemplate ?: setList ?: continue
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
        }
        val durationTicks = timeline?.durationTicks
            ?: template.attr("duration")?.toLongOrNull()?.takeIf { it > 0 }
            ?: return refuse("DASH SegmentTemplate has no positive duration")
        val startNumber = template.attr("startNumber")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: return refuse("DASH startNumber is not a valid number")
        } ?: 1L
        val presentationTimeOffset = template.attr("presentationTimeOffset")?.toLongOrNull() ?: 0L
        if (presentationTimeOffset != 0L) return refuse("DASH presentationTimeOffset is not supported")
        val startTicks = timeline?.startTicks ?: 0L
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
            ),
        )
    }

    /** A static SegmentList: the listed URLs and byte ranges, in order. A live list needs a refresh, so it is refused. */
    private fun planList(
        live: Boolean,
        timing: DashTiming,
        topBaseUrls: List<Element>,
        representation: Element,
        id: String,
        bandwidth: Long,
        list: Element,
    ): DashPlanOutcome {
        if (live) return refuse("Live DASH SegmentList needs a manifest refresh, which is not supported yet")
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
        for (entry in entries) {
            val media = entry.attr("media")
            val range = entry.attr("mediaRange")?.let { parseRange(it) ?: return refuse("DASH SegmentURL byte range is not valid") }
            if (media == null && range == null) return refuse("DASH SegmentURL has no media URL or byte range")
            if (media == null && !hasBase) return refuse("DASH SegmentURL has no media URL and no BaseURL")
            segments += DashListedSegment(media = media, range = range)
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
                segmentCount = segments.size.toLong(),
                timing = timing,
                representationBaseUrl = representationBaseUrl(representation),
                listedInitialization = initialization,
                listedSegments = segments,
            ),
        )
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

    private sealed class TimelineOutcome {
        class Regular(val timeline: Timeline) : TimelineOutcome()
        class Refused(val reason: String) : TimelineOutcome()
    }

    /**
     * Reads the SegmentTimeline of [template], or returns null when it has none. A live manifest may end its timeline
     * with an open repeat (r="-1"), which runs to the clock; any other entry must be listed. Refuses an irregular or
     * overlong timeline, because the recorder derives every segment time from one start and one duration.
     */
    private fun timelineOf(template: Element, live: Boolean): TimelineOutcome? {
        val timelines = template.childElements("SegmentTimeline")
        if (timelines.isEmpty()) return null
        if (timelines.size != 1) return TimelineOutcome.Refused("DASH SegmentTemplate has more than one SegmentTimeline")
        val entries = timelines[0].childElements("S")
        if (entries.isEmpty()) return TimelineOutcome.Refused("DASH SegmentTimeline has no entries")
        if (entries.size > MAX_TIMELINE_ENTRIES) return TimelineOutcome.Refused("DASH SegmentTimeline has too many entries")
        var duration = 0L
        var start = 0L
        var expectedStart = 0L
        var listed = 0L
        try {
            for ((index, entry) in entries.withIndex()) {
                val d = entry.attr("d")?.toLongOrNull()?.takeIf { it in 1L..MAX_TIMELINE_TICKS }
                    ?: return TimelineOutcome.Refused("DASH SegmentTimeline entry needs a positive duration")
                val repeat = entry.attr("r")?.let { raw ->
                    raw.toIntOrNull() ?: return TimelineOutcome.Refused("DASH SegmentTimeline repeat count is not a number")
                } ?: 0
                if (repeat < -1) return TimelineOutcome.Refused("DASH SegmentTimeline repeat count is not valid")
                val t = entry.attr("t")?.let { raw ->
                    raw.toLongOrNull()?.takeIf { it >= 0 }
                        ?: return TimelineOutcome.Refused("DASH SegmentTimeline start time is not valid")
                }
                if (index == 0) {
                    duration = d
                    start = t ?: 0L
                    expectedStart = start
                } else {
                    if (d != duration) {
                        return TimelineOutcome.Refused("DASH SegmentTimeline durations differ; irregular timelines are not supported")
                    }
                    if (t != null && t != expectedStart) {
                        return TimelineOutcome.Refused("DASH SegmentTimeline is not contiguous; irregular timelines are not supported")
                    }
                }
                if (repeat == -1) {
                    // An open repeat runs to the clock, which only a live presentation has.
                    if (index != entries.lastIndex) {
                        return TimelineOutcome.Refused("DASH SegmentTimeline repeats to the end only in its last entry")
                    }
                    if (!live) return TimelineOutcome.Refused("DASH SegmentTimeline repeats to the end only in live manifests")
                    break
                }
                listed = Math.addExact(listed, repeat + 1L)
                if (listed > MAX_SEGMENTS) return TimelineOutcome.Refused("DASH manifest has too many segments")
                expectedStart = Math.addExact(expectedStart, Math.multiplyExact(repeat + 1L, d))
            }
        } catch (overflow: ArithmeticException) {
            return TimelineOutcome.Refused("DASH SegmentTimeline is too long")
        }
        return TimelineOutcome.Regular(Timeline(start, duration, listed))
    }

    private val UNSUPPORTED_ELEMENTS = listOf("ContentProtection", "SegmentBase")

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

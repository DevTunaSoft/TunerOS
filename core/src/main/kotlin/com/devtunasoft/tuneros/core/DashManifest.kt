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
    val baseUrl: String?,
    val representationId: String,
    val bandwidth: Long,
    val initializationTemplate: String?,
    val mediaTemplate: String,
    val startNumber: Long,
    val segmentDurationSeconds: Double,
    val timing: DashTiming,
)

/** How segment numbers relate to time. */
sealed class DashTiming {
    /** A finished presentation. Its media segments are counted from the presentation duration. */
    data class Static(val mediaPresentationDurationSeconds: Double) : DashTiming()

    /**
     * A live presentation. Segment N is complete at availabilityStart + (N - startNumber + 1) * duration, so the
     * recorder follows the clock rather than a segment list. availabilityTimeOffset and availabilityTimeComplete
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
 * video-only, whose representation uses a SegmentTemplate with `$Number$` and a fixed duration. A dynamic manifest
 * must carry an availabilityStartTime with a UTC offset, and the recorder follows that clock. It refuses everything
 * else with a fixed reason: separate audio or text renditions, protection, timelines, lists, DTDs and unknown
 * template tokens. No manifest text, URL or exception message is kept.
 */
object DashManifest {
    const val MAX_MANIFEST_CHARS = 1_000_000
    private const val MAX_TEMPLATE_CHARS = 512

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
        val topBaseUrls = root.childElements("BaseURL")
        if (topBaseUrls.size > 1 || root.getElementsByTagName("BaseURL").length != topBaseUrls.size) {
            return refuse("Nested or repeated DASH BaseURL is not supported")
        }
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
        var chosen: Element? = null
        var chosenTemplate: Element? = null
        var chosenBandwidth = -1L
        for (representation in set.childElements("Representation")) {
            val template = representation.childElements("SegmentTemplate").firstOrNull() ?: setTemplate ?: continue
            val bandwidth = representation.attr("bandwidth")?.toLongOrNull() ?: 0L
            if (bandwidth > chosenBandwidth) {
                chosen = representation
                chosenTemplate = template
                chosenBandwidth = bandwidth
            }
        }
        val representation = chosen ?: return refuse("No DASH representation has a SegmentTemplate")
        val template = chosenTemplate ?: return refuse("No DASH representation has a SegmentTemplate")
        val id = representation.attr("id") ?: return refuse("DASH representation has no id")
        val media = template.attr("media") ?: return refuse("DASH SegmentTemplate has no media template")
        val initialization = template.attr("initialization")
        val timescale = template.attr("timescale")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 } ?: return refuse("DASH timescale is not a positive number")
        } ?: 1L
        val durationTicks = template.attr("duration")?.toLongOrNull()?.takeIf { it > 0 }
            ?: return refuse("DASH SegmentTemplate has no positive duration")
        val startNumber = template.attr("startNumber")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: return refuse("DASH startNumber is not a valid number")
        } ?: 1L
        val presentationTimeOffset = template.attr("presentationTimeOffset")?.toLongOrNull() ?: 0L
        if (presentationTimeOffset != 0L) return refuse("DASH presentationTimeOffset is not supported")
        if (expand(media, id, chosenBandwidth, startNumber) == null) {
            return refuse("DASH media template uses a token that is not supported")
        }
        if (initialization != null && expand(initialization, id, chosenBandwidth, startNumber) == null) {
            return refuse("DASH initialization template uses a token that is not supported")
        }
        return DashPlanOutcome.Planned(
            DashPlan(
                baseUrl = topBaseUrls.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                representationId = id,
                bandwidth = chosenBandwidth,
                initializationTemplate = initialization,
                mediaTemplate = media,
                startNumber = startNumber,
                segmentDurationSeconds = durationTicks.toDouble() / timescale.toDouble(),
                timing = timing,
            ),
        )
    }

    /**
     * Expands `$RepresentationID$`, `$Bandwidth$`, `$Number$` and `$Number%0Nd$` (N from 1 to 9), with `$$` as a
     * literal dollar sign. Any other token returns null, so a template the recorder cannot honour is refused.
     */
    fun expand(template: String, representationId: String, bandwidth: Long, number: Long): String? {
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

    private val UNSUPPORTED_ELEMENTS = listOf("ContentProtection", "SegmentList", "SegmentBase", "SegmentTimeline")

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

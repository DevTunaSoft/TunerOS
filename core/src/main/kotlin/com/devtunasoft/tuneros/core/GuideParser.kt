package com.devtunasoft.tuneros.core

import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Inclusive-start, exclusive-end programme retention window (epoch seconds). */
data class GuideWindow(val fromEpochSeconds: Long, val toEpochSeconds: Long) {
    init { require(toEpochSeconds > fromEpochSeconds) }
}

/** Secure SAX XMLTV parser with optional bounded-time retention and timestamp warnings. */
object GuideParser {
    const val MAX_PROGRAMS = 250_000
    const val MAX_CHANNELS = 25_000
    private val withOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val withoutOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val noOffsetPattern = Regex("""\d{14}""")
    const val MAX_TITLE_CHARS = 256
    const val MAX_CHANNEL_NAME_CHARS = 256
    const val MAX_DESCRIPTION_CHARS = 2048

    fun parse(input: InputStream, maxPrograms: Int = MAX_PROGRAMS,
              maxChannels: Int = MAX_CHANNELS, fallbackZone: ZoneId = ZoneOffset.UTC,
              window: GuideWindow? = null, includeDescriptions: Boolean = true): GuideSnapshot {
        require(maxPrograms in 1..MAX_PROGRAMS && maxChannels in 1..MAX_CHANNELS)
        val channels = LinkedHashMap<String, String>()
        val programs = ArrayList<GuideProgram>()
        var skippedPrograms = 0
        var skippedChannels = 0
        var assumedZone = 0
        var invalidTimestamps = 0
        var truncatedTitles = 0
        var truncatedChannelNames = 0
        var truncatedDescriptions = 0
        val handler = object : DefaultHandler() {
            private var channelId: String? = null
            private var programme = false
            private var start: Long? = null
            private var stop: Long? = null
            private var title: String? = null
            private var description: String? = null
            private var capture: String? = null
            private var captureLimit = 0
            private var captureTruncated = false
            private var assumedForProgramme = false
            private val buffer = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                when (name) {
                    "channel" -> { channelId = attrs.getValue("id")?.take(256); programme = false }
                    "programme" -> {
                        programme = true
                        channelId = attrs.getValue("channel")?.take(256)
                        val from = parseTime(attrs.getValue("start"), fallbackZone)
                        val to = parseTime(attrs.getValue("stop"), fallbackZone)
                        start = from.first; stop = to.first
                        assumedForProgramme = from.second || to.second
                        title = null; description = null
                    }
                    "display-name", "title", "desc" -> {
                        if (name != "desc" || includeDescriptions) {
                            capture = name
                            captureLimit = when (name) {
                                "title" -> MAX_TITLE_CHARS
                                "display-name" -> MAX_CHANNEL_NAME_CHARS
                                else -> MAX_DESCRIPTION_CHARS
                            }
                            captureTruncated = false
                            buffer.setLength(0)
                        }
                    }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture != null) {
                    val available = captureLimit - buffer.length
                    if (length > available) captureTruncated = true
                    if (available > 0) buffer.append(ch, start, minOf(length, available))
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                if (capture == name) {
                    if (captureTruncated) when (name) {
                        "title" -> truncatedTitles++
                        "display-name" -> truncatedChannelNames++
                        "desc" -> truncatedDescriptions++
                    }
                    val value = buffer.toString().trim()
                    when (name) {
                        "display-name" -> if (!programme && channelId != null && value.isNotEmpty() &&
                            !channels.containsKey(channelId)) {
                            if (channels.size < maxChannels) channels[channelId!!] = value else skippedChannels++
                        }
                        "title" -> if (programme) title = value
                        "desc" -> if (programme) description = value
                    }
                    capture = null; buffer.setLength(0)
                }
                when (name) {
                    "programme" -> {
                        val id = channelId
                        val from = start
                        val to = stop
                        if (assumedForProgramme) assumedZone++
                        if (from == null || to == null) invalidTimestamps++
                        val programmeTitle = title?.takeIf { it.isNotBlank() }
                        if (id != null && from != null && to != null && to > from && programmeTitle != null &&
                            (window == null || (to > window.fromEpochSeconds && from < window.toEpochSeconds))) {
                            if (programs.size < maxPrograms) {
                                programs += GuideProgram(id, programmeTitle, from, to, description)
                            } else skippedPrograms++
                        }
                        channelId = null; programme = false
                    }
                    "channel" -> channelId = null
                }
            }
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
        }
        factory.newSAXParser().parse(input, handler)
        val warnings = buildList {
            if (skippedPrograms > 0) add("$skippedPrograms programmes omitted: limit $maxPrograms")
            if (skippedChannels > 0) add("$skippedChannels channel names omitted: limit $maxChannels")
            if (assumedZone > 0) add("$assumedZone programmes lacked timezone: assumed " +
                if (fallbackZone == ZoneOffset.UTC) "UTC" else fallbackZone.id)
            if (invalidTimestamps > 0) add("$invalidTimestamps programmes had invalid timestamps")
            if (truncatedTitles > 0) add("$truncatedTitles programme titles shortened to $MAX_TITLE_CHARS characters")
            if (truncatedChannelNames > 0) add("$truncatedChannelNames channel names shortened to $MAX_CHANNEL_NAME_CHARS characters")
            if (truncatedDescriptions > 0) add("$truncatedDescriptions descriptions shortened to $MAX_DESCRIPTION_CHARS characters")
        }
        return GuideSnapshot(programs, channels, warnings)
    }

    private fun parseTime(raw: String?, fallbackZone: ZoneId): Pair<Long?, Boolean> {
        if (raw == null) return null to false
        val value = raw.trim()
        return if (noOffsetPattern.matches(value)) {
            runCatching { LocalDateTime.parse(value, withoutOffset).atZone(fallbackZone).toEpochSecond() }.getOrNull() to true
        } else {
            runCatching { OffsetDateTime.parse(value, withOffset).toEpochSecond() }.getOrNull() to false
        }
    }
}

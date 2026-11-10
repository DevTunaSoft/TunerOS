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

/** Secure SAX XMLTV parser with explicit truncation and timestamp warnings. */
object GuideParser {
    const val MAX_PROGRAMS = 250_000
    const val MAX_CHANNELS = 25_000
    private val withOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val withoutOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(input: InputStream, maxPrograms: Int = MAX_PROGRAMS,
              maxChannels: Int = MAX_CHANNELS, fallbackZone: ZoneId = ZoneOffset.UTC): GuideSnapshot {
        require(maxPrograms in 1..MAX_PROGRAMS && maxChannels in 1..MAX_CHANNELS)
        val channels = LinkedHashMap<String, String>()
        val programs = ArrayList<GuideProgram>()
        var skippedPrograms = 0
        var skippedChannels = 0
        var assumedZone = 0
        var invalidTimestamps = 0
        val handler = object : DefaultHandler() {
            private var channelId: String? = null
            private var programme = false
            private var start: Long? = null
            private var stop: Long? = null
            private var title: String? = null
            private var description: String? = null
            private var capture: String? = null
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
                    "display-name", "title", "desc" -> { capture = name; buffer.setLength(0) }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture != null && buffer.length < 4096) {
                    buffer.append(ch, start, minOf(length, 4096 - buffer.length))
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                if (capture == name) {
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
                        if (id != null && from != null && to != null && to > from && !title.isNullOrBlank()) {
                            if (programs.size < maxPrograms) programs += GuideProgram(id, title!!, from, to, description)
                            else skippedPrograms++
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
            if (assumedZone > 0) add("$assumedZone programmes lacked timezone: assumed $fallbackZone")
            if (invalidTimestamps > 0) add("$invalidTimestamps programmes had invalid timestamps")
        }
        return GuideSnapshot(programs, channels, warnings)
    }

    private fun parseTime(raw: String?, fallbackZone: ZoneId): Pair<Long?, Boolean> {
        if (raw == null) return null to false
        val value = raw.trim()
        return if (value.matches(Regex("""\d{14}"""))) {
            runCatching { LocalDateTime.parse(value, withoutOffset).atZone(fallbackZone).toEpochSecond() }.getOrNull() to true
        } else {
            runCatching { OffsetDateTime.parse(value, withOffset).toEpochSecond() }.getOrNull() to false
        }
    }
}

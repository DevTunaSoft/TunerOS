package com.devtunasoft.tuneros.core

import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Streaming XMLTV SAX import: rejects DTD/external entities and excessive guide size. */
object GuideParser {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    const val MAX_PROGRAMS = 250_000
    const val MAX_CHANNELS = 25_000

    fun parse(input: InputStream): GuideSnapshot {
        val channels = LinkedHashMap<String, String>()
        val programs = ArrayList<GuideProgram>()
        val handler = object : DefaultHandler() {
            private var currentChannel: String? = null
            private var start: Long? = null
            private var stop: Long? = null
            private var title: String? = null
            private var description: String? = null
            private var element: String? = null
            private val buffer = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                element = name
                buffer.setLength(0)
                when (name) {
                    "channel" -> currentChannel = attrs.getValue("id")?.take(256)
                    "programme" -> {
                        currentChannel = attrs.getValue("channel")?.take(256)
                        start = parseTime(attrs.getValue("start"))
                        stop = parseTime(attrs.getValue("stop"))
                        title = null; description = null
                    }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (element in setOf("display-name", "title", "desc") && buffer.length < 4096) {
                    buffer.append(ch, start, minOf(length, 4096 - buffer.length))
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                val value = buffer.toString().trim()
                when (name) {
                    "display-name" -> if (currentChannel != null && !channels.containsKey(currentChannel) && value.isNotEmpty()) {
                        if (channels.size == MAX_CHANNELS) throw IllegalArgumentException("Guide exceeds channel limit")
                        channels[currentChannel!!] = value
                    }
                    "title" -> title = value
                    "desc" -> description = value
                    "programme" -> {
                        val id = currentChannel
                        val from = start
                        val to = stop
                        if (id != null && from != null && to != null && to > from && !title.isNullOrBlank()) {
                            if (programs.size == MAX_PROGRAMS) throw IllegalArgumentException("Guide exceeds program limit")
                            programs += GuideProgram(id, title!!, from, to, description)
                        }
                        currentChannel = null
                    }
                }
                element = null
                buffer.setLength(0)
            }
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        factory.newSAXParser().parse(input, handler)
        return GuideSnapshot(programs, channels)
    }

    private fun parseTime(raw: String?): Long? {
        if (raw == null) return null
        return runCatching { OffsetDateTime.parse(raw.trim(), timestamp).toEpochSecond() }.getOrNull()
    }
}

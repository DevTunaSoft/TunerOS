package com.devtunasoft.tuneros.core

import java.io.BufferedReader
import java.io.Reader
import java.security.MessageDigest

/** Bounded, streaming M3U parser. Does not download streams while importing. */
object PlaylistParser {
    const val MAX_CHANNELS = 25_000
    const val MAX_LINE_LENGTH = 32_768

    fun parse(reader: Reader, maxChannels: Int = MAX_CHANNELS): List<Channel> {
        require(maxChannels in 1..MAX_CHANNELS)
        val result = ArrayList<Channel>()
        val input = BufferedReader(reader)
        var pending: Pending? = null
        var userAgent: String? = null
        var referer: String? = null
        var totalLines = 0
        while (true) {
            val raw = input.readLine() ?: break
            totalLines++
            if (totalLines > 200_000) throw IllegalArgumentException("Playlist exceeds line limit")
            if (raw.length > MAX_LINE_LENGTH) throw IllegalArgumentException("Playlist line too long")
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pending = parseExtinf(line)
                    userAgent = null
                    referer = null
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val pair = line.substringAfter(':').split('=', limit = 2)
                    if (pair.size == 2) when (pair[0].lowercase()) {
                        "http-user-agent" -> userAgent = pair[1].take(512)
                        "http-referrer", "http-referer" -> referer = pair[1].take(2048)
                    }
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    val metadata = pending ?: continue
                    pending = null
                    val url = line.trim()
                    if (!isAllowedUrl(url) || metadata.name.isBlank()) continue
                    if (result.size == maxChannels) throw IllegalArgumentException("Playlist exceeds channel limit")
                    val stableKey = metadata.guideId?.takeIf { it.isNotBlank() } ?: url
                    result += Channel(
                        id = stableId(stableKey), name = metadata.name.take(256),
                        streamUrl = url, guideId = metadata.guideId,
                        logoUrl = metadata.logo?.takeIf(::isAllowedUrl),
                        group = metadata.group?.take(128),
                        userAgent = userAgent, referer = referer,
                    )
                    userAgent = null
                    referer = null
                }
            }
        }
        return result.distinctBy { it.id to it.streamUrl }
    }

    private fun isAllowedUrl(value: String) =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private data class Pending(val name: String, val guideId: String?, val logo: String?, val group: String?)

    private val attribute = Regex("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")

    private fun parseExtinf(line: String): Pending {
        val attrs = attribute.findAll(line).associate {
            it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }.ifEmpty { it.groupValues[4] })
        }
        val display = line.substringAfterLast(',', "").trim()
        val name = display.ifBlank { attrs["tvg-name"].orEmpty() }
        return Pending(name, attrs["tvg-id"], attrs["tvg-logo"], attrs["group-title"])
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
}

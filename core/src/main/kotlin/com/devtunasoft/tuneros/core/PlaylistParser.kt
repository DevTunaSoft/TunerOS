package com.devtunasoft.tuneros.core

import java.io.BufferedReader
import java.io.Reader
import java.net.URI
import java.security.MessageDigest

data class PlaylistImport(val channels: List<Channel>, val warnings: List<String>)

/** Streaming M3U parser with a bounded line reader, source-scoped IDs, and truncation warnings. */
object PlaylistParser {
    const val MAX_CHANNELS = 25_000
    const val MAX_LINE_LENGTH = 32_768
    private const val MAX_LINES = 200_000
    private const val MAX_SHARED_HEADERS = 512

    fun parse(reader: Reader, maxChannels: Int = MAX_CHANNELS): List<Channel> =
        parseReport(reader, maxChannels).channels

    fun parseReport(reader: Reader, maxChannels: Int = MAX_CHANNELS, sourceId: String = "default"): PlaylistImport {
        require(maxChannels in 1..MAX_CHANNELS)
        val result = ArrayList<Channel>()
        val seen = HashSet<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        // Construct the source prefix once; avoid a 30-KiB stable-key String
        // and its UTF-8 copy for every imported channel.
        val sourcePrefix = (sourceId + "\u0000").toByteArray(Charsets.UTF_8)
        val sharedHeaders = HashMap<String, String>()
        fun shareHeader(value: String?): String? {
            if (value == null) return null
            sharedHeaders[value]?.let { return it }
            // Repeated provider headers are common, but a feed with unique values must
            // not create an unbounded string-interning map.
            if (sharedHeaders.size < MAX_SHARED_HEADERS) sharedHeaders[value] = value
            return value
        }
        val input = if (reader is BufferedReader) reader else BufferedReader(reader)
        var pending: Pending? = null
        var userAgent: String? = null
        var referer: String? = null
        var totalLines = 0
        var skipped = 0
        while (true) {
            val raw = BoundedLines.read(input, MAX_LINE_LENGTH, "Playlist") ?: break
            totalLines++
            if (totalLines > MAX_LINES) throw IllegalArgumentException("Playlist exceeds $MAX_LINES lines")
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pending = parseExtinf(line); userAgent = null; referer = null
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
                    if (!isAllowedUrl(line) || metadata.name.isBlank()) continue
                    val id = stableId(digest, sourcePrefix, metadata.guideId, line)
                    if (result.size == maxChannels) {
                        // The accepted-ID set must stop growing once the channel cap is hit.
                        if (id !in seen) skipped++
                        continue
                    }
                    if (!seen.add(id)) continue
                    result += Channel(
                        id = id, name = metadata.name.take(256), streamUrl = line,
                        guideId = metadata.guideId, logoUrl = metadata.logo?.takeIf(::isAllowedUrl),
                        group = metadata.group?.take(128), userAgent = shareHeader(userAgent), referer = shareHeader(referer),
                    )
                    userAgent = null; referer = null
                }
            }
        }
        val warnings = if (skipped > 0) listOf("$skipped channels omitted: limit $maxChannels") else emptyList()
        return PlaylistImport(result, warnings)
    }

    private fun isAllowedUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && value.none { it.code < 32 || it.code == 127 }
    }.getOrDefault(false)

    private data class Pending(val name: String, val guideId: String?, val logo: String?, val group: String?)
    private val attribute = Regex("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")

    private fun parseExtinf(line: String): Pending {
        // Large provider feeds often contain unused metadata attributes.
        // Extract only consumed values: no per-channel attribute map,
        // Pair objects or lowercase copies. Duplicate keys still use last wins.
        var tvgName: String? = null
        var guideId: String? = null
        var logo: String? = null
        var group: String? = null
        for (match in attribute.findAll(line)) {
            val key = match.groups[1]?.value ?: continue
            val value = match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: ""
            when {
                key.equals("tvg-name", ignoreCase = true) -> tvgName = value
                key.equals("tvg-id", ignoreCase = true) -> guideId = value
                key.equals("tvg-logo", ignoreCase = true) -> logo = value
                key.equals("group-title", ignoreCase = true) -> group = value
            }
        }
        val display = line.substringAfterLast(',', "").trim()
        return Pending(display.ifBlank { tvgName.orEmpty() }, guideId, logo, group)
    }


    private val hexDigits = "0123456789abcdef".toCharArray()

    /** Stream UTF-8 fields with the original zero separators; IDs stay byte-identical. */
    private fun stableId(
        digest: MessageDigest,
        sourcePrefix: ByteArray,
        guideId: String?,
        streamUrl: String,
    ): String {
        digest.reset()
        digest.update(sourcePrefix)
        if (!guideId.isNullOrEmpty()) digest.update(guideId.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(streamUrl.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val chars = CharArray(24)
        for (index in 0 until 12) {
            val byte = hash[index].toInt() and 0xff
            chars[index * 2] = hexDigits[byte ushr 4]
            chars[index * 2 + 1] = hexDigits[byte and 0x0f]
        }
        return String(chars)
    }
}

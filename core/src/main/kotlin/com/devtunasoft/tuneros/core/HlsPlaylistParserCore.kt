package com.devtunasoft.tuneros.core

import java.io.BufferedReader
import java.io.Reader

sealed interface HlsPlaylistSnapshot

data class HlsVariant(
    val uri: String,
    val bandwidth: Long?,
    val audioGroup: String? = null,
    val videoGroup: String? = null,
)

data class HlsMasterPlaylist(
    val variants: List<HlsVariant>,
    val externalAudioGroups: Set<String> = emptySet(),
    val externalVideoGroups: Set<String> = emptySet(),
) : HlsPlaylistSnapshot

data class HlsByteRange(
    val length: Long,
    val offset: Long,
)

data class HlsSegment(
    val sequence: Long,
    val uri: String,
    val durationSeconds: Double?,
    val byteRange: HlsByteRange?,
    val discontinuity: Boolean,
    val keyMethod: String,
    val keyUri: String?,
    val initializationMapUri: String?,
)

data class HlsMediaPlaylist(
    val mediaSequence: Long,
    val targetDurationSeconds: Long?,
    val endList: Boolean,
    val segments: List<HlsSegment>,
    val latestSequence: Long? = segments.lastOrNull()?.sequence,
) : HlsPlaylistSnapshot

/**
 * Bounded HLS manifest parser for DVR capture planning.
 *
 * It stores segment metadata only, never media bytes. Callers can pass
 * [afterSequenceExclusive] when polling a live playlist so the returned list contains
 * only unseen segments instead of a growing history.
 */
object HlsPlaylistParserCore {
    const val MAX_LINE_LENGTH = 16_384
    const val MAX_LINES = 50_000
    const val MAX_TOTAL_CHARACTERS = 2_000_000
    const val MAX_RESULT_SEGMENTS = 10_000
    const val MAX_VARIANTS = 256
    const val MAX_EXTERNAL_RENDITION_GROUPS = 256

    private data class PendingRange(val length: Long, val offset: Long?)

    fun parse(
        reader: Reader,
        afterSequenceExclusive: Long? = null,
    ): HlsPlaylistSnapshot {
        val input = if (reader is BufferedReader) reader else BufferedReader(reader)
        val variants = ArrayList<HlsVariant>()
        val externalAudioGroups = HashSet<String>()
        val externalVideoGroups = HashSet<String>()
        val segments = ArrayList<HlsSegment>()
        var totalLines = 0
        var totalCharacters = 0
        var mediaSequence = 0L
        var nextSequence = 0L
        var mediaSequenceSeen = false
        var targetDuration: Long? = null
        var endList = false
        var pendingDuration: Double? = null
        var pendingRange: PendingRange? = null
        var pendingDiscontinuity = false
        var currentKeyMethod = "NONE"
        var currentKeyUri: String? = null
        var currentMapUri: String? = null
        var pendingVariantBandwidth: Long? = null
        var pendingVariantAudioGroup: String? = null
        var pendingVariantVideoGroup: String? = null
        var expectingVariantUri = false
        var lastRangeUri: String? = null
        var lastRangeEnd: Long? = null
        var sawHeader = false
        var sawMediaTag = false
        var sawSegmentUri = false
        var latestSequence: Long? = null

        while (true) {
            val raw = BoundedLines.read(input, MAX_LINE_LENGTH, "HLS") ?: break
            totalLines++
            totalCharacters = Math.addExact(totalCharacters, raw.length + 1)
            if (totalLines > MAX_LINES) {
                throw IllegalArgumentException("HLS playlist exceeds $MAX_LINES lines")
            }
            if (totalCharacters > MAX_TOTAL_CHARACTERS) {
                throw IllegalArgumentException(
                    "HLS playlist exceeds $MAX_TOTAL_CHARACTERS characters",
                )
            }
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (!sawHeader) {
                require(line == "#EXTM3U") { "HLS playlist must begin with #EXTM3U" }
                sawHeader = true
                continue
            }

            when {
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    require(segments.isEmpty()) { "Mixed HLS master and media playlist" }
                    expectingVariantUri = true
                    pendingVariantBandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull()
                    pendingVariantAudioGroup = attribute(line, "AUDIO")
                    pendingVariantVideoGroup = attribute(line, "VIDEO")
                }
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    require(segments.isEmpty()) { "Mixed HLS master and media playlist" }
                    val type = attribute(line, "TYPE")?.uppercase()
                    val group = attribute(line, "GROUP-ID")
                    val uri = attribute(line, "URI")
                    if (group != null && uri != null && type in setOf("AUDIO", "VIDEO")) {
                        val target = if (type == "AUDIO") externalAudioGroups else externalVideoGroups
                        if (target.size >= MAX_EXTERNAL_RENDITION_GROUPS && group !in target) {
                            throw IllegalArgumentException(
                                "HLS master exceeds $MAX_EXTERNAL_RENDITION_GROUPS external rendition groups",
                            )
                        }
                        target += group
                    }
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    require(variants.isEmpty()) { "Mixed HLS master and media playlist" }
                    mediaSequence = line.substringAfter(':').trim().toLong()
                    nextSequence = mediaSequence
                    mediaSequenceSeen = true
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                    targetDuration = line.substringAfter(':').trim().toLong()
                    sawMediaTag = true
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDouble()
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    pendingRange = parseRange(line.substringAfter(':').trim())
                    sawMediaTag = true
                }
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    pendingDiscontinuity = true
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    currentKeyMethod = attribute(line, "METHOD")?.uppercase() ?: "NONE"
                    currentKeyUri = if (currentKeyMethod == "NONE") null else attribute(line, "URI")
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    currentMapUri = attribute(line, "URI")
                        ?: throw IllegalArgumentException("EXT-X-MAP missing URI")
                    sawMediaTag = true
                }
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    endList = true
                    sawMediaTag = true
                }
                line.startsWith("#") -> Unit
                expectingVariantUri -> {
                    if (variants.size >= MAX_VARIANTS) {
                        throw IllegalArgumentException("HLS master exceeds $MAX_VARIANTS variants")
                    }
                    variants += HlsVariant(
                        uri = line,
                        bandwidth = pendingVariantBandwidth,
                        audioGroup = pendingVariantAudioGroup,
                        videoGroup = pendingVariantVideoGroup,
                    )
                    pendingVariantBandwidth = null
                    pendingVariantAudioGroup = null
                    pendingVariantVideoGroup = null
                    expectingVariantUri = false
                }
                else -> {
                    require(variants.isEmpty()) { "Mixed HLS master and media playlist" }
                    if (!mediaSequenceSeen && !sawSegmentUri) nextSequence = 0L

                    val range = pendingRange?.let { pending ->
                        val offset = pending.offset ?: run {
                            require(lastRangeUri == line && lastRangeEnd != null) {
                                "Implicit HLS byte-range offset requires a preceding range for the same URI"
                            }
                            lastRangeEnd!!
                        }
                        HlsByteRange(pending.length, offset)
                    }

                    if (range != null) {
                        lastRangeUri = line
                        lastRangeEnd = Math.addExact(range.offset, range.length)
                    } else {
                        lastRangeUri = null
                        lastRangeEnd = null
                    }

                    val sequence = nextSequence++
                    sawSegmentUri = true
                    latestSequence = sequence
                    if (afterSequenceExclusive == null || sequence > afterSequenceExclusive) {
                        if (segments.size >= MAX_RESULT_SEGMENTS) {
                            throw IllegalArgumentException(
                                "HLS parse result exceeds $MAX_RESULT_SEGMENTS segments",
                            )
                        }
                        segments += HlsSegment(
                            sequence = sequence,
                            uri = line,
                            durationSeconds = pendingDuration,
                            byteRange = range,
                            discontinuity = pendingDiscontinuity,
                            keyMethod = currentKeyMethod,
                            keyUri = currentKeyUri,
                            initializationMapUri = currentMapUri,
                        )
                    }
                    pendingDuration = null
                    pendingRange = null
                    pendingDiscontinuity = false
                }
            }
        }

        require(sawHeader) { "Empty HLS playlist" }
        require(!expectingVariantUri) { "HLS variant missing URI" }
        if (variants.isNotEmpty()) {
            return HlsMasterPlaylist(
                variants = variants,
                externalAudioGroups = externalAudioGroups,
                externalVideoGroups = externalVideoGroups,
            )
        }
        require(sawMediaTag || segments.isNotEmpty()) { "HLS playlist contains no media data" }
        return HlsMediaPlaylist(
            mediaSequence = mediaSequence,
            targetDurationSeconds = targetDuration,
            endList = endList,
            segments = segments,
            latestSequence = latestSequence,
        )
    }

    private fun parseRange(value: String): PendingRange {
        val parts = value.split('@', limit = 2)
        val length = parts[0].toLong()
        require(length > 0) { "HLS byte-range length must be positive" }
        val offset = parts.getOrNull(1)?.toLong()
        require(offset == null || offset >= 0) { "HLS byte-range offset must be non-negative" }
        return PendingRange(length, offset)
    }

    private fun attribute(line: String, name: String): String? {
        val body = line.substringAfter(':', "")
        var index = 0
        while (index < body.length) {
            while (index < body.length && (body[index] == ',' || body[index].isWhitespace())) index++
            val equals = body.indexOf('=', index)
            if (equals < 0) return null
            val key = body.substring(index, equals).trim()
            index = equals + 1
            val value: String
            if (index < body.length && body[index] == '"') {
                index++
                val end = body.indexOf('"', index)
                if (end < 0) throw IllegalArgumentException("Unterminated HLS attribute")
                value = body.substring(index, end)
                index = end + 1
            } else {
                val comma = body.indexOf(',', index).let { if (it < 0) body.length else it }
                value = body.substring(index, comma).trim()
                index = comma
            }
            if (key.equals(name, ignoreCase = true)) return value
        }
        return null
    }
}

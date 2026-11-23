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
    val initializationMapByteRange: HlsByteRange? = null,
    val keyIv: String? = null,
    val keyFormat: String? = null,
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
        var currentKeyIv: String? = null
        var currentKeyFormat: String? = null
        var currentMapUri: String? = null
        var currentMapByteRange: HlsByteRange? = null
        var pendingVariantBandwidth: Long? = null
        var pendingVariantAudioGroup: String? = null
        var pendingVariantVideoGroup: String? = null
        var expectingVariantUri = false
        var lastRangeUri: String? = null
        var lastRangeEnd: Long? = null
        var sawHeader = false
        var sawMasterTag = false
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
                    require(!sawMediaTag && !sawSegmentUri) { "Mixed HLS master and media playlist" }
                    sawMasterTag = true
                    expectingVariantUri = true
                    pendingVariantBandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull()
                    pendingVariantAudioGroup = attribute(line, "AUDIO")
                    pendingVariantVideoGroup = attribute(line, "VIDEO")
                }
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    require(!sawMediaTag && !sawSegmentUri) { "Mixed HLS master and media playlist" }
                    sawMasterTag = true
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
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
                    require(variants.isEmpty()) { "Mixed HLS master and media playlist" }
                    // RFC 8216 allows one media sequence tag, before the first segment. A later tag would renumber the
                    // segments after it, so the playlist is refused instead.
                    require(!mediaSequenceSeen && !sawSegmentUri) {
                        "HLS media sequence must appear once, before the first segment"
                    }
                    mediaSequence = longField(line.substringAfter(':').trim())
                    nextSequence = mediaSequence
                    mediaSequenceSeen = true
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
                    targetDuration = longField(line.substringAfter(':').trim())
                    sawMediaTag = true
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
                    pendingDuration = doubleField(line.substringAfter(':').substringBefore(',').trim())
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
                    pendingRange = parseRange(line.substringAfter(':').trim())
                    sawMediaTag = true
                }
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
                    pendingDiscontinuity = true
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
                    currentKeyMethod = attribute(line, "METHOD")?.uppercase() ?: "NONE"
                    currentKeyUri = if (currentKeyMethod == "NONE") null else attribute(line, "URI")
                    currentKeyIv = if (currentKeyMethod == "NONE") null else attribute(line, "IV")
                    currentKeyFormat = if (currentKeyMethod == "NONE") null else attribute(line, "KEYFORMAT")
                    sawMediaTag = true
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
                    currentMapUri = attribute(line, "URI")
                        ?: throw IllegalArgumentException("EXT-X-MAP missing URI")
                    currentMapByteRange = attribute(line, "BYTERANGE")?.let { value ->
                        val range = parseRange(value)
                        HlsByteRange(
                            range.length,
                            range.offset ?: throw IllegalArgumentException("EXT-X-MAP byte range needs an offset"),
                        )
                    }
                    sawMediaTag = true
                }
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    require(!sawMasterTag) { "Mixed HLS master and media playlist" }
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
                    require(!sawMasterTag && variants.isEmpty()) {
                        "Mixed HLS master and media playlist"
                    }
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
                        lastRangeEnd = try {
                            Math.addExact(range.offset, range.length)
                        } catch (overflow: ArithmeticException) {
                            throw IllegalArgumentException("HLS byte range end exceeds Long.MAX_VALUE")
                        }
                    } else {
                        lastRangeUri = null
                        lastRangeEnd = null
                    }

                    // A segment numbered Long.MAX_VALUE is valid as the last one, but the next sequence cannot be
                    // represented. Refuse instead of wrapping to Long.MIN_VALUE and yielding duplicate/out-of-order
                    // numbers on a long or malicious playlist.
                    require(latestSequence != Long.MAX_VALUE) { "HLS segment sequence exceeds Long.MAX_VALUE" }
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
                            initializationMapByteRange = currentMapByteRange,
                            keyIv = currentKeyIv,
                            keyFormat = currentKeyFormat,
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
        val length = longField(parts[0])
        require(length > 0) { "HLS byte-range length must be positive" }
        val offset = parts.getOrNull(1)?.let { longField(it) }
        require(offset == null || offset >= 0) { "HLS byte-range offset must be non-negative" }
        return PendingRange(length, offset)
    }

    /** Generic refusals: NumberFormatException echoes the raw tag, which may carry URL or credential material. */
    private fun longField(value: String): Long = try {
        value.toLong()
    } catch (malformed: NumberFormatException) {
        throw IllegalArgumentException("HLS numeric field is malformed")
    }

    private fun doubleField(value: String): Double = try {
        value.toDouble()
    } catch (malformed: NumberFormatException) {
        throw IllegalArgumentException("HLS numeric field is malformed")
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

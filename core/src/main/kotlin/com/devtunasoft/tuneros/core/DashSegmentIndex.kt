package com.devtunasoft.tuneros.core

import java.nio.ByteBuffer

/**
 * A DASH SegmentBase index: the ISO/IEC 14496-12 `sidx` box that a SegmentBase names with its indexRange. The first
 * media byte is the byte after the box plus first_offset, and each later reference follows the one before it. Parsing
 * is bounded. It refuses a hierarchical index, an empty or zero-duration reference, an unknown version, a box that
 * does not fit the bytes it was read from, and a reference that names a byte past [MAX_BYTE]. No byte of the index is
 * kept.
 */
object DashSegmentIndex {
    /** The largest index the recorder reads, so the read itself is bounded. */
    const val MAX_INDEX_BYTES = 200_000L
    private const val MAX_REFERENCES = 10_000

    /** The largest byte position a reference may name: the bound the manifest's own byte ranges have. */
    const val MAX_BYTE = 1_000_000_000_000_000L

    /** One media subsegment: its byte range in the file, and its duration in the index timescale. */
    data class Reference(val range: HlsByteRange, val durationTicks: Long)

    sealed class Outcome {
        data class Parsed(val timescale: Long, val references: List<Reference>) : Outcome()
        data class Refused(val reason: String) : Outcome()
    }

    /**
     * Parses the index box that occupies [indexRange] of its file. [bytes] must be exactly that range, read from the
     * origin, and no larger than [MAX_INDEX_BYTES].
     */
    fun parse(bytes: ByteArray, indexRange: HlsByteRange): Outcome {
        if (indexRange.length > MAX_INDEX_BYTES) return Outcome.Refused("DASH SegmentBase index is too large")
        if (bytes.size.toLong() != indexRange.length) {
            return Outcome.Refused("DASH SegmentBase index range is not the bytes requested")
        }
        if (bytes.size < 8) return Outcome.Refused("DASH SegmentBase index is truncated")
        val buffer = ByteBuffer.wrap(bytes)
        var boxSize = buffer.getInt(0).toLong() and 0xFFFF_FFFFL
        var pos = 8
        if (boxSize == 1L) {
            if (bytes.size < 16) return Outcome.Refused("DASH SegmentBase index is truncated")
            boxSize = buffer.getLong(8)
            pos = 16
        }
        if (boxSize == 0L) return Outcome.Refused("DASH SegmentBase index does not end within its range")
        if (boxSize < pos || boxSize > bytes.size) return Outcome.Refused("DASH SegmentBase index does not fit its range")
        val size = boxSize.toInt()
        if (String(bytes, 4, 4, Charsets.US_ASCII) != "sidx") return Outcome.Refused("DASH SegmentBase index is not a sidx box")

        // FullBox version and flags, then reference_ID and timescale.
        if (!fits(size, pos, 4 + 4 + 4)) return Outcome.Refused("DASH SegmentBase index is truncated")
        val version = bytes[pos].toInt() and 0xFF
        pos += 4 + 4
        val timescale = buffer.getInt(pos).toLong() and 0xFFFF_FFFFL
        pos += 4
        val firstOffset: Long
        when (version) {
            0 -> {
                if (!fits(size, pos, 8)) return Outcome.Refused("DASH SegmentBase index is truncated")
                pos += 4 // earliest_presentation_time
                firstOffset = buffer.getInt(pos).toLong() and 0xFFFF_FFFFL
                pos += 4
            }
            1 -> {
                if (!fits(size, pos, 16)) return Outcome.Refused("DASH SegmentBase index is truncated")
                pos += 8 // earliest_presentation_time
                firstOffset = buffer.getLong(pos)
                pos += 8
                if (firstOffset < 0) return Outcome.Refused("DASH SegmentBase index offsets overflow")
            }
            else -> return Outcome.Refused("DASH SegmentBase index version is not supported")
        }
        if (!fits(size, pos, 2 + 2)) return Outcome.Refused("DASH SegmentBase index is truncated")
        pos += 2 // reserved
        val count = buffer.getShort(pos).toInt() and 0xFFFF
        pos += 2
        if (timescale == 0L) return Outcome.Refused("DASH SegmentBase index timescale is not positive")
        if (count == 0) return Outcome.Refused("DASH SegmentBase index has no references")
        if (count > MAX_REFERENCES) return Outcome.Refused("DASH SegmentBase index has too many references")
        if (!fits(size, pos, 12 * count)) return Outcome.Refused("DASH SegmentBase index is truncated")

        return try {
            var offset = Math.addExact(indexRange.offset + boxSize, firstOffset)
            val references = ArrayList<Reference>(count)
            for (index in 0 until count) {
                val word = buffer.getInt(pos).toLong() and 0xFFFF_FFFFL
                val duration = buffer.getInt(pos + 4).toLong() and 0xFFFF_FFFFL
                pos += 12
                if ((word ushr 31) == 1L) return Outcome.Refused("DASH SegmentBase hierarchical index is not supported")
                val length = word and 0x7FFF_FFFFL
                if (length == 0L) return Outcome.Refused("DASH SegmentBase index has an empty subsegment")
                if (duration == 0L) return Outcome.Refused("DASH SegmentBase index has a zero-duration subsegment")
                if (offset > MAX_BYTE - (length - 1)) return Outcome.Refused("DASH SegmentBase index offsets are too large")
                references += Reference(HlsByteRange(length = length, offset = offset), duration)
                offset = Math.addExact(offset, length)
            }
            Outcome.Parsed(timescale, references)
        } catch (overflow: ArithmeticException) {
            Outcome.Refused("DASH SegmentBase index offsets overflow")
        }
    }

    /** Whether [count] bytes starting at [from] lie inside the first [size] bytes of the box. */
    private fun fits(size: Int, from: Int, count: Int): Boolean = from >= 0 && from.toLong() + count <= size
}

package com.devtunasoft.tuneros.core

import java.util.Locale

/**
 * A bounded window of the most recent live segments, kept for time-shifted playback. The oldest segments leave the window
 * first, once either limit is passed, and the caller deletes their files. A segment larger than the whole byte budget is
 * refused, so the window never holds more than [maxBytes]. Sequence numbers only increase, so a playlist built from the
 * window never repeats or reorders a segment.
 */
class TimeshiftWindow(val maxSegments: Int, val maxBytes: Long) {
    init {
        require(maxSegments > 0)
        require(maxBytes > 0)
    }

    /**
     * One recorded segment: its media sequence number, its duration, its size, the name of its file in the window, and
     * whether the source marked a discontinuity before it.
     */
    data class Segment(
        val sequence: Long,
        val durationSeconds: Double,
        val bytes: Long,
        val fileName: String,
        val discontinuity: Boolean = false,
    ) {
        init {
            require(sequence >= 0)
            require(durationSeconds > 0.0 && durationSeconds.isFinite())
            require(bytes >= 0)
            // A file name names a file in the window's own directory. A path separator would reach outside it.
            require(fileName.isNotEmpty() && '/' !in fileName)
        }
    }

    private val window = ArrayDeque<Segment>()
    private var retainedBytes = 0L

    /** The number of segments in the window. */
    val size: Int get() = window.size

    /** The bytes the window holds. Never more than [maxBytes]. */
    val bytes: Long get() = retainedBytes

    /**
     * Adds [segment] as the newest and returns the segments that left the window, oldest first. A segment larger than the
     * whole byte budget is not kept; it is returned at once, so the caller can delete its file.
     */
    fun add(segment: Segment): List<Segment> {
        require(window.isEmpty() || segment.sequence > window.last().sequence)
        if (segment.bytes > maxBytes) return listOf(segment)
        window.addLast(segment)
        retainedBytes += segment.bytes
        val evicted = ArrayList<Segment>()
        while (window.size > maxSegments || retainedBytes > maxBytes) {
            val oldest = window.removeFirst()
            retainedBytes -= oldest.bytes
            evicted += oldest
        }
        return evicted
    }

    /** The segments in the window, oldest first. */
    fun segments(): List<Segment> = window.toList()

    /**
     * An HLS media playlist for the window, naming its segments by file. A live window has no end tag, so it keeps growing.
     * The target duration is the longest segment rounded up, which HLS requires.
     */
    fun playlist(): String {
        val out = StringBuilder()
        out.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        if (window.isEmpty()) {
            out.append("#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
            return out.toString()
        }
        val target = Math.ceil(window.maxOf { it.durationSeconds }).toLong().coerceAtLeast(1L)
        out.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(window.first().sequence).append('\n')
        for (segment in window) {
            if (segment.discontinuity) out.append("#EXT-X-DISCONTINUITY\n")
            out.append("#EXTINF:")
                .append(String.format(Locale.ROOT, "%.3f", segment.durationSeconds))
                .append(",\n")
            out.append(segment.fileName).append('\n')
        }
        return out.toString()
    }

    /** Empties the window and returns the file names it held, so the caller can delete them. */
    fun clear(): List<String> {
        val names = window.map { it.fileName }
        window.clear()
        retainedBytes = 0L
        return names
    }
}

package com.devtunasoft.tuneros.core

import java.io.IOException
import java.io.OutputStream

/**
 * Where a timeshift keeps its segment files and its playlist. The app supplies a private directory; the core names no
 * paths, and nothing outside a timeshift's own ring is ever created, replaced or deleted through this interface.
 */
interface TimeshiftRing {
    /** Creates a new file named [fileName] and returns a stream that writes it. The caller closes the stream. */
    fun create(fileName: String): OutputStream

    /** Deletes [fileName]. A file that is already gone is not an error. */
    fun delete(fileName: String)

    /** Replaces the playlist with [text] in one step, so a reader never sees a part-written playlist. */
    fun replacePlaylist(text: String)
}

/**
 * Stores each completed live segment in a bounded [TimeshiftWindow] and keeps the window's playlist current.
 *
 * The HLS engine writes every segment into [output]. This class points [output] at a new file at each segment boundary,
 * so one segment is one file. A file stays only while its segment is in the window: when a segment leaves, or when it is
 * larger than the whole budget, its file is deleted. [finish] deletes what is left, including a segment that was
 * part-written when the recording stopped, and leaves a playlist that lists nothing. Use from the recording thread only.
 */
class TimeshiftCapture(
    private val window: TimeshiftWindow,
    private val ring: TimeshiftRing,
) : HlsSegmentListener {
    private var nextFile = 0L
    private var openName: String? = null
    private var openStream: OutputStream? = null
    private var finished = false

    /** The stream the HLS engine writes segment bytes into. It always writes to the file of the segment in progress. */
    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = currentStream().write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = currentStream().write(b, off, len)
    }

    init {
        openNextFile()
    }

    private fun currentStream(): OutputStream = openStream ?: error("No timeshift segment file is open")

    private fun openNextFile() {
        val name = "seg-${nextFile++}.ts"
        openStream = ring.create(name)
        openName = name
    }

    override fun onSegmentComplete(sequence: Long, durationSeconds: Double, bytes: Long, discontinuity: Boolean) {
        check(!finished) { "timeshift is finished" }
        val name = checkNotNull(openName)
        checkNotNull(openStream).close()
        openStream = null
        openName = null
        val evicted = window.add(TimeshiftWindow.Segment(sequence, durationSeconds, bytes, name, discontinuity))
        evicted.forEach { ring.delete(it.fileName) }
        // A segment that cannot fit is never listed, so the playlist would skip it. Stop instead of listing a gap.
        if (evicted.any { it.fileName == name }) {
            throw IOException("timeshift segment is larger than its whole byte budget")
        }
        ring.replacePlaylist(window.playlist())
        openNextFile()
    }

    /**
     * Stops the timeshift. Closes and deletes the file being written, deletes every segment still in the window, and
     * replaces the playlist with an empty one. Calling it again does nothing.
     */
    fun finish() {
        if (finished) return
        finished = true
        openStream?.let { stream -> runCatching { stream.close() } }
        openName?.let { ring.delete(it) }
        openStream = null
        openName = null
        window.clear().forEach { ring.delete(it) }
        ring.replacePlaylist(window.playlist())
    }
}

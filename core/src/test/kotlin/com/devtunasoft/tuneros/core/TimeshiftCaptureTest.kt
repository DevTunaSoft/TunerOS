package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeshiftCaptureTest {
    /** Keeps every file's bytes, which files were closed, each deletion and each playlist written. */
    private class MemoryRing : TimeshiftRing {
        private val contents = LinkedHashMap<String, ByteArrayOutputStream>()
        val closed = HashSet<String>()
        val deleted = ArrayList<String>()
        val playlists = ArrayList<String>()

        override fun create(fileName: String): OutputStream {
            check(fileName !in contents) { "created twice: $fileName" }
            val bytes = ByteArrayOutputStream()
            contents[fileName] = bytes
            return object : OutputStream() {
                override fun write(b: Int) = bytes.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
                override fun close() {
                    closed += fileName
                }
            }
        }

        override fun delete(fileName: String) {
            deleted += fileName
        }

        override fun replacePlaylist(text: String) {
            playlists += text
        }

        fun bytesOf(fileName: String): ByteArray = contents.getValue(fileName).toByteArray()

        /** Files created and not yet deleted. */
        fun live(): Set<String> = contents.keys - deleted.toSet()
    }

    /** Serves [polls] one per playlist load, repeating the last one. Each segment's bytes are its sequence number. */
    private class ScriptedSource(private val polls: List<HlsMediaPlaylist>) : HlsCaptureSource {
        private var next = 0

        override fun loadPlaylist(url: String, afterSequenceExclusive: Long?): LoadedHlsPlaylist {
            val playlist = polls[minOf(next, polls.lastIndex)]
            next++
            return LoadedHlsPlaylist(url, playlist)
        }

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result {
            val sequence = url.substringAfterLast('/').removeSuffix(".ts").toLong()
            output.write(byteArrayOf(sequence.toByte()))
            onProgress(1L)
            return StreamRecorder.Result(1L, StreamRecorder.StopReason.END_OF_STREAM)
        }

        override fun loadKey(url: String): ByteArray = error("this fixture has no keys")
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun assertBytesEqual(expected: ByteArray, actual: ByteArray) = assertEquals(expected.toList(), actual.toList())

    @Test fun eachCompletedSegmentIsOneFileAndTheOutputMovesOnToTheNextFile() {
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 10, maxBytes = 10_000), ring)
        capture.output.write(bytes(1, 2, 3))
        capture.onSegmentComplete(sequence = 7, durationSeconds = 2.0, bytes = 3, discontinuity = false)
        capture.output.write(bytes(9))
        capture.onSegmentComplete(sequence = 8, durationSeconds = 2.0, bytes = 1, discontinuity = false)
        assertBytesEqual(bytes(1, 2, 3), ring.bytesOf("seg-0.ts"))
        assertBytesEqual(bytes(9), ring.bytesOf("seg-1.ts"))
        assertEquals(setOf("seg-0.ts", "seg-1.ts"), ring.closed, "each completed segment's file is closed; the next one is still open")
    }

    @Test fun evictedSegmentsAreDeletedAndThePlaylistIsReplacedWithTheWindow() {
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 2, maxBytes = 10_000), ring)
        for (sequence in 0L..2L) {
            capture.output.write(bytes(sequence.toInt()))
            capture.onSegmentComplete(sequence, 2.0, 1, discontinuity = false)
        }
        assertEquals(listOf("seg-0.ts"), ring.deleted)
        assertEquals(3, ring.playlists.size)
        val last = ring.playlists.last()
        assertTrue(last.contains("#EXT-X-MEDIA-SEQUENCE:1\n"), last)
        assertTrue(last.contains("seg-1.ts\n") && last.contains("seg-2.ts\n"), last)
        assertTrue(!last.contains("seg-0.ts"), last)
    }

    @Test fun aSegmentLargerThanTheWholeBudgetFailsTheRecordingAndItsFileIsDeleted() {
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 10, maxBytes = 4), ring)
        capture.output.write(ByteArray(5))
        assertFailsWith<IOException> { capture.onSegmentComplete(0, 2.0, 5, discontinuity = false) }
        capture.finish()
        assertEquals(listOf("seg-0.ts"), ring.deleted)
        assertTrue(ring.live().isEmpty(), "left on disk: ${ring.live()}")
    }

    @Test fun finishDeletesThePartWrittenSegmentAndEveryFileStillInTheWindow() {
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 10, maxBytes = 10_000), ring)
        capture.output.write(bytes(1))
        capture.onSegmentComplete(0, 2.0, 1, discontinuity = false)
        capture.output.write(bytes(2, 2))
        capture.finish()
        assertTrue(ring.live().isEmpty(), "left on disk: ${ring.live()}")
        assertTrue("seg-1.ts" in ring.closed, "the part-written file is closed before it is deleted")
    }

    @Test fun finishCanBeCalledTwiceAndWritingAfterItIsRefused() {
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 10, maxBytes = 10_000), ring)
        capture.finish()
        val deletions = ring.deleted.size
        capture.finish()
        assertEquals(deletions, ring.deleted.size)
        assertFailsWith<IllegalStateException> { capture.output.write(1) }
    }

    @Test fun finishLeavesAPlaylistThatListsNothing() {
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 10, maxBytes = 10_000), ring)
        capture.output.write(bytes(1))
        capture.onSegmentComplete(0, 2.0, 1, discontinuity = false)
        capture.finish()
        assertTrue(!ring.playlists.last().contains("EXTINF"), ring.playlists.last())
    }

    @Test fun aDiscontinuityIsMarkedInThePlaylistBeforeItsSegment() {
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 10, maxBytes = 10_000), ring)
        capture.output.write(bytes(1))
        capture.onSegmentComplete(0, 2.0, 1, discontinuity = false)
        capture.output.write(bytes(2))
        capture.onSegmentComplete(1, 2.0, 1, discontinuity = true)
        val playlist = ring.playlists.last()
        assertTrue(playlist.contains("#EXT-X-DISCONTINUITY\n#EXTINF:2.000,\nseg-1.ts\n"), playlist)
    }

    @Test fun theEngineWritesEachLiveSegmentIntoItsOwnFileAndTheWindowKeepsTheNewest() {
        val url = "https://example.test/live.m3u8"
        fun segment(sequence: Long) = HlsSegment(sequence, "$sequence.ts", 2.0, null, false, "NONE", null, null)
        val source = ScriptedSource(
            listOf(
                HlsMediaPlaylist(10L, 2L, false, listOf(segment(10))),
                HlsMediaPlaylist(10L, 2L, false, listOf(segment(10), segment(11))),
                HlsMediaPlaylist(10L, 2L, true, listOf(segment(10), segment(11), segment(12))),
            ),
        )
        val ring = MemoryRing()
        val capture = TimeshiftCapture(TimeshiftWindow(maxSegments = 2, maxBytes = 10_000), ring)
        val result = HlsCaptureEngine().record(
            url, capture.output, 1_000, source, { false }, { 100 }, {}, segmentListener = capture,
        )
        val playlistBeforeStop = ring.playlists.last()
        capture.finish()

        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertBytesEqual(bytes(10), ring.bytesOf("seg-0.ts"))
        assertBytesEqual(bytes(11), ring.bytesOf("seg-1.ts"))
        assertBytesEqual(bytes(12), ring.bytesOf("seg-2.ts"))
        assertEquals(listOf("seg-0.ts"), ring.deleted.take(1), "the oldest segment leaves while recording")
        assertTrue(playlistBeforeStop.contains("#EXT-X-MEDIA-SEQUENCE:11\n"), playlistBeforeStop)
        assertTrue(ring.live().isEmpty(), "finish leaves nothing on disk: ${ring.live()}")
    }
}

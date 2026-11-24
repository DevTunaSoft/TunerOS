package com.devtunasoft.tuneros.qa42

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * QA-42 JVM source-contract test over the committed playback-soak42 fixture.
 *
 * Pure JVM and JUnit 4 only: no android.* stub, device, network, URL,
 * credential or media. The host gate in scripts/qa42 is authoritative; this
 * test independently re-evaluates the same pinned soak model in the app unit
 * suite.
 */
class Qa42SoakContractTest {
    private data class Channel(val id: String, val start: Long)
    private data class Step(
        val index: Int, val headers: String, val recording: Int,
        val reuse: Int, val created: Int, val released: Int, val live: Int,
    )
    private data class Segment(
        val seq: Long, val durationMs: Long, val bytes: Long,
        val evictions: Int, val firstEvicted: Long,
        val retained: Int, val retainedBytes: Long, val oversize: Int,
    )

    companion object {
        private fun fixtureDir(): File {
            var dir = File(System.getProperty("user.dir") ?: ".")
            for (ignored in 0..8) {
                val candidate = File(dir, "qa/fixtures/playback-soak42")
                if (File(candidate, "dimensions.tsv").isFile) return candidate
                dir = dir.parentFile ?: break
            }
            throw IllegalStateException("playback-soak42 fixtures not found from user.dir")
        }

        private fun rows(name: String): List<List<String>> =
            fixtureDir().resolve(name).readText().split("\n")
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split("\t") }

        private fun pairs(name: String): Map<String, String> {
            val rows = rows(name)
            assertTrue("pair file $name", rows.all { it.size == 2 })
            val map = rows.associate { it[0] to it[1] }
            assertEquals(rows.size, map.size)
            return map
        }

        private fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun digestOf(lines: List<String>): String = sha256(lines.joinToString("\n"))

        private fun parseChannels(): List<Channel> {
            val rows = rows("epg.tsv")
            assertEquals("channel_id\tchannel_name\tstart_epoch_ms\tseed", rows.first().joinToString("\t"))
            return rows.drop(1).map { Channel(it[0], it[2].toLong()) }
        }

        private fun parseSteps(): List<Step> {
            val rows = rows("soak.tsv")
            assertEquals(
                "step\tchannel\theaders_id\trecording\texpected_reuse\texpected_created\texpected_released\texpected_live",
                rows.first().joinToString("\t"),
            )
            return rows.drop(1).map {
                Step(it[0].toInt(), it[2], it[3].toInt(), it[4].toInt(), it[5].toInt(), it[6].toInt(), it[7].toInt())
            }
        }

        private fun parseSegments(): List<Segment> {
            val rows = rows("segments.tsv")
            assertEquals(
                "seq\tduration_ms\tbytes\texpected_evictions\texpected_evict_first\texpected_retained_count\texpected_retained_bytes\texpected_oversize",
                rows.first().joinToString("\t"),
            )
            return rows.drop(1).map {
                Segment(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3].toInt(),
                    it[4].toLong(), it[5].toInt(), it[6].toLong(), it[7].toInt())
            }
        }

        private class Replay(
            val trace: List<Segment>,
            val window: List<Long>,
            val retained: Long,
            var byteEvictions: Int,
            var countEvictions: Int,
            var peakWindow: Int,
            var oversize: Int,
        )

        private fun replaySegments(segments: List<Segment>, maxSegments: Int, maxBytes: Long): Replay {
            val window = ArrayDeque<Pair<Long, Long>>()
            var retained = 0L
            val trace = mutableListOf<Segment>()
            var byteEvictions = 0
            var countEvictions = 0
            var peakWindow = 0
            var oversize = 0
            for (segment in segments) {
                val evicted = mutableListOf<Pair<Long, Long>>()
                if (segment.bytes > maxBytes) {
                    oversize++
                    evicted.add(segment.seq to segment.bytes)
                } else {
                    window.addLast(segment.seq to segment.bytes)
                    retained += segment.bytes
                    while (window.size > maxSegments || retained > maxBytes) {
                        val reasonCount = window.size > maxSegments
                        val old = window.removeFirst()
                        retained -= old.second
                        evicted.add(old)
                        if (reasonCount) countEvictions++ else byteEvictions++
                    }
                }
                peakWindow = maxOf(peakWindow, window.size)
                trace.add(
                    segment.copy(
                        evictions = evicted.size,
                        firstEvicted = evicted.firstOrNull()?.first ?: -1L,
                        retained = window.size,
                        retainedBytes = retained,
                    ),
                )
            }
            return Replay(trace, window.map { it.first }, retained, byteEvictions, countEvictions, peakWindow, oversize)
        }

        private fun playlist(window: List<Long>, segments: List<Segment>): String {
            if (window.isEmpty()) return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n"
            val durations = segments.associate { it.seq to it.durationMs }
            val target = maxOf(1L, kotlin.math.ceil(window.maxOf { durations[it]!! } / 1000.0).toLong())
            val lines = mutableListOf(
                "#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:$target",
                "#EXT-X-MEDIA-SEQUENCE:${window.first()}",
            )
            for (seq in window) {
                lines.add("#EXTINF:${String.format(Locale.ROOT, "%.3f", durations[seq]!! / 1000.0)},")
                lines.add("seg-$seq.ts")
            }
            return lines.joinToString("\n") + "\n"
        }
    }

    @Test
    fun fixtureShapeAndPolicyMarkersArePinned() {
        val dims = pairs("dimensions.tsv")
        val markers = pairs("markers.tsv")
        val expect = pairs("expectations.tsv")
        assertEquals(19, dims.size)
        assertEquals(8, markers.size)
        assertEquals("1", dims["schema.version"])
        assertTrue(markers.values.all { it == "1" })
        assertTrue(markers.containsKey("byte_budget_enforced"))
        assertEquals(19, expect.size)
    }

    @Test
    fun longEpgWindowExpandsContiguouslyAndRecyclesRows() {
        val dims = pairs("dimensions.tsv")
        val expect = pairs("expectations.tsv")
        val channels = parseChannels()
        val perChannel = dims["epg.programs_per_channel"]!!.toInt()
        val slotMs = dims["epg.slot_seconds"]!!.toLong() * 1000L
        assertEquals(96, channels.size)
        val expanded = mutableListOf<String>()
        val byChannel = mutableMapOf<String, MutableList<Long>>()
        for (channel in channels) {
            val starts = byChannel.getOrPut(channel.id) { mutableListOf() }
            for (index in 0 until perChannel) {
                val begin = channel.start + index * slotMs
                starts.add(begin)
                expanded.add("${channel.id}\t$index\t$begin\t${begin + slotMs}\tQA Program ${channel.id}-${"%02d".format(index)}")
            }
        }
        assertEquals(4608, expanded.size)
        assertEquals(expect["epg.programs"]!!.toInt(), expanded.size)
        assertEquals(expect["epg.digest"], digestOf(expanded))
        for (starts in byChannel.values) {
            assertEquals(perChannel, starts.size)
            for (i in 0 until starts.size - 1) assertEquals(starts[i] + slotMs, starts[i + 1])
        }
        val pageRows = dims["epg.page_rows"]!!.toInt()
        val pages = (channels.size + pageRows - 1) / pageRows
        assertEquals(4, pages)
        val slots = dims["epg.recycled_slots"]!!.toInt()
        val step = dims["epg.sample_step"]!!.toInt()
        val limit = channels.size - pageRows
        for (sample in 0 until 8) {
            val first = (sample * step) % (limit + 1)
            val occupied = IntArray(slots)
            var reuse = 0
            for (index in first until first + pageRows) {
                val slot = index % slots
                if (occupied[slot] > 0) reuse++
                occupied[slot]++
                assertTrue(index in first until first + pageRows)
            }
            assertTrue(occupied.max()!! <= slots)
            assertTrue(reuse > 0)
        }
    }

    @Test
    fun soakLedgerIsBoundedAndLeakFree() {
        val dims = pairs("dimensions.tsv")
        val expect = pairs("expectations.tsv")
        val steps = parseSteps()
        assertEquals(96, steps.size)
        var current: String? = null
        var created = 0
        var released = 0
        var live = 0
        var reuse = 0
        var peak = 0
        for (step in steps) {
            val canReuse = current != null && step.recording == 0 && current == step.headers
            if (canReuse) {
                reuse++
            } else {
                if (live > 0) { released++; live-- }
                created++
                live++
                current = step.headers
            }
            peak = maxOf(peak, live)
            assertEquals("step ${step.index}", step.reuse, if (canReuse) 1 else 0)
            assertEquals("step ${step.index}", step.created, created)
            assertEquals("step ${step.index}", step.released, released)
            assertEquals("step ${step.index}", step.live, live)
        }
        released++
        live--
        assertEquals(0, live)
        assertEquals(created, released)
        assertEquals(expect["soak.reuse"]!!.toInt(), reuse)
        assertEquals(expect["soak.created"]!!.toInt(), created)
        assertEquals(expect["soak.released"]!!.toInt(), released)
        assertEquals(expect["soak.peak_live"]!!.toInt(), peak)
        assertTrue(peak in 1..dims["soak.max_live_players"]!!.toInt())
    }

    @Test
    fun timeshiftRetentionHonoursBothCapsAndRefusesOversize() {
        val dims = pairs("dimensions.tsv")
        val expect = pairs("expectations.tsv")
        val segments = parseSegments()
        val maxSegments = dims["timeshift.max_segments"]!!.toInt()
        val maxBytes = dims["timeshift.max_bytes"]!!.toLong()
        val replay = replaySegments(segments, maxSegments, maxBytes)
        assertEquals(160, segments.size)
        for ((input, actual) in segments.zip(replay.trace)) {
            assertEquals("seq ${input.seq}", input.evictions, actual.evictions)
            assertEquals("seq ${input.seq}", input.firstEvicted, actual.firstEvicted)
            assertEquals("seq ${input.seq}", input.retained, actual.retained)
            assertEquals("seq ${input.seq}", input.retainedBytes, actual.retainedBytes)
            assertEquals("seq ${input.seq}", input.oversize, actual.oversize)
        }
        assertTrue(replay.retained <= maxBytes)
        assertTrue(replay.window.size <= maxSegments)
        assertEquals(expect["segment.retained_count"]!!.toInt(), replay.window.size)
        assertEquals(expect["segment.retained_bytes"]!!.toLong(), replay.retained)
        assertEquals(expect["segment.byte_evictions"]!!.toInt(), replay.byteEvictions)
        assertEquals(expect["segment.count_evictions"]!!.toInt(), replay.countEvictions)
        assertEquals(expect["segment.peak_retained_count"]!!.toInt(), replay.peakWindow)
        assertTrue(replay.byteEvictions > 0 && replay.countEvictions > 0)
        assertEquals(maxSegments, replay.peakWindow)
        assertEquals(expect["segment.oversize"]!!.toInt(), replay.oversize)
        assertEquals(
            expect["segment.evictions"]!!.toInt(),
            segments.sumOf { it.evictions },
        )
    }

    @Test
    fun livePlaylistAndFinishCleanupArePinned() {
        val expect = pairs("expectations.tsv")
        val segments = parseSegments()
        val dims = pairs("dimensions.tsv")
        val replay = replaySegments(segments, dims["timeshift.max_segments"]!!.toInt(), dims["timeshift.max_bytes"]!!.toLong())
        val text = playlist(replay.window, segments)
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(!text.contains("#EXT-X-ENDLIST"))
        assertEquals(expect["playlist.digest"], sha256(text))
        val deleted = replay.window.size + 1
        assertEquals(expect["segment.retained_count"]!!.toInt() + 1, deleted)
        assertTrue(replay.window.isNotEmpty())
    }
}

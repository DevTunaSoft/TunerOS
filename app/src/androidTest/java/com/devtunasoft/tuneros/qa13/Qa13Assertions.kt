package com.devtunasoft.tuneros.qa13

import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * QA-13 JVM/Android mirror of the bounded live-window model. No Android imports here, so these
 * assertions run on the JVM and inside the instrumented APK. All data is derived in memory from
 * fixed constants: no provider URL, credential, PIN, secret or media payload literal appears here.
 */
object Qa13Assertions {
    private const val VERSION = "qa13-mirror-v1"
    private const val SEED = 20261123L
    private const val CHANNELS = 8
    private const val CAP = 7
    private const val RETENTION = 60_000L
    private const val BACK = 10_000L
    private const val STEP = 4_000L
    private const val SLOTS = 14_000L

    const val EXPECTED_DIGEST = "687e06ebb5c450576c3c0e8a8de3195fa647b7c4d7ffda9dd692a43a19faa8ff"

    private data class Win(val channel: Int, val from: Long, val to: Long)
    private data class State(
        val retained: Int,
        val intervals: List<Pair<Long, Long>>,
        val overlap: Int,
        val touch: Int,
        val edge: Long,
    )
    private data class Outcome(val accepted: Boolean, val position: Long, val clip: String)

    private fun sha(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun secret(): String {
        val mix = SEED * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        return (10_000_000L + Math.floorMod(mix, 90_000_000L)).toString()
    }

    private fun build(): List<Win> {
        val slots = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 13, 14, 15, 16)
        val out = ArrayList<Win>()
        for (channel in 1..CHANNELS) {
            for (slot in slots) {
                val from = 200_000L + slot * STEP
                out.add(Win(channel, from, from + SLOTS))
            }
        }
        out.add(Win(CHANNELS + 1, 10_000L, 17_000L))
        out.add(Win(CHANNELS + 2, 10_000L, 17_000L))
        out.add(Win(CHANNELS + 2, 17_000L, 24_000L))
        return out
    }

    private fun stateFor(windows: List<Win>, channel: Int): State {
        val own = windows.filter { it.channel == channel }
        if (own.isEmpty()) return State(0, emptyList(), 0, 0, 0L)
        val edge = own.maxOf { it.to }
        val floor = edge - RETENTION
        val clipped = own.map { maxOf(it.from, floor) to it.to }
            .filter { it.first < it.second }
            .sortedBy { it.first }
            .takeLast(CAP)
        if (clipped.isEmpty()) return State(0, emptyList(), 0, 0, edge)
        val intervals = ArrayList<Pair<Long, Long>>()
        var from = clipped[0].first
        var to = clipped[0].second
        var overlap = 0
        var touch = 0
        for (i in 1 until clipped.size) {
            val nextFrom = clipped[i].first
            val nextTo = clipped[i].second
            if (nextFrom <= to) {
                if (nextFrom < to) overlap++ else if (nextFrom == to) touch++
                to = maxOf(to, nextTo)
            } else {
                intervals.add(from to to)
                from = nextFrom
                to = nextTo
            }
        }
        intervals.add(from to to)
        return State(clipped.size, intervals, overlap, touch, edge)
    }

    private fun covered(state: State, position: Long): Boolean =
        state.intervals.any { position >= it.first && position <= it.second }

    private fun evaluate(state: State, mode: String, position: Long): Outcome {
        if (state.intervals.isEmpty()) return Outcome(false, -1L, "EMPTY_WINDOW")
        val earliest = state.intervals[0].first
        val raw = when (mode) {
            "LIVE" -> state.edge
            "BACK" -> if (position < earliest || position > state.edge) {
                return Outcome(false, -1L, "POSITION_OUT_OF_WINDOW")
            } else maxOf(0L, position - BACK)
            else -> position
        }
        var target = raw
        var clip = "NONE"
        when {
            target < earliest -> { target = earliest; clip = "LOW" }
            target > state.edge -> { target = state.edge; clip = "HIGH" }
            !covered(state, target) -> {
                var lower = earliest
                var upper = state.edge
                for (interval in state.intervals) {
                    if (target > interval.second) lower = interval.second
                    if (target < interval.first) { upper = interval.first; break }
                }
                target = if (target - lower <= upper - target) lower else upper
                clip = "GAP"
            }
        }
        return Outcome(true, target, clip)
    }

    private fun artifact(outcomes: List<Outcome>, states: List<State>): String = buildList {
        add(VERSION)
        add("channels=$CHANNELS")
        add("cap=$CAP")
        add("overlap=${states.sumOf { it.overlap.toLong() }}")
        add("touch=${states.sumOf { it.touch.toLong() }}")
        add("unions=${states.sumOf { it.intervals.size.toLong() }}")
        add("accepted=${outcomes.count { it.accepted }}")
        add("rejected=${outcomes.count { !it.accepted }}")
        add("low=${outcomes.count { it.clip == "LOW" }}")
        add("high=${outcomes.count { it.clip == "HIGH" }}")
        add("gap=${outcomes.count { it.clip == "GAP" }}")
    }.joinToString("\n", postfix = "\n")

    fun digest(): String {
        val windows = build()
        val states = (1..CHANNELS + 3).map { stateFor(windows, it) }
        val outcomes = ArrayList<Outcome>()
        val positions = listOf(199_500L, 200_000L, 201_000L, 239_000L, 277_999L, 278_000L, 278_500L, 247_000L)
        val modes = listOf("AT", "LIVE", "BACK")
        for (channel in 1..CHANNELS + 3) {
            positions.forEachIndexed { index, position ->
                outcomes.add(evaluate(states[channel - 1], modes[(index + 2) % modes.size], position))
            }
        }
        return sha(artifact(outcomes, states).toByteArray(Charsets.UTF_8))
    }

    fun reportLines(): List<String> {
        val windows = build()
        val states = (1..CHANNELS + 3).map { stateFor(windows, it) }
        val positions = listOf(199_500L, 200_000L, 201_000L, 239_000L, 277_999L, 278_000L, 278_500L, 247_000L)
        val modes = listOf("AT", "LIVE", "BACK")
        val outcomes = ArrayList<Outcome>()
        for (channel in 1..CHANNELS + 3) {
            positions.forEachIndexed { index, position ->
                outcomes.add(evaluate(states[channel - 1], modes[(index + 2) % modes.size], position))
            }
        }
        fun check(id: String, ok: Boolean, detail: String) = "CHECK\t${if (ok) "PASS" else "FAIL"}\t$id\t$detail"
        val normal = states.take(CHANNELS)
        val checks = listOf(
            check("mirror-artifact-digest", digest() == EXPECTED_DIGEST, "sha256=pinned"),
            check("mirror-window-count", windows.size == CHANNELS * 14 + 3, "windows=${windows.size}"),
            check("mirror-overlap-count", normal.sumOf { it.overlap } == CHANNELS * 5,
                "pairs=${normal.sumOf { it.overlap }}"),
            check("mirror-cap-and-retention", normal.all { it.retained == CAP }, "cap=$CAP"),
            check("mirror-touch-not-overlap", states[CHANNELS + 1].touch == 1 &&
                states[CHANNELS + 1].overlap == 0, "touch=${states[CHANNELS + 1].touch}"),
            check("mirror-union-shape", normal.all { it.intervals.size == 2 }, "channels=${normal.size}"),
            check("mirror-seek-bounds", outcomes.filter { it.accepted }.all {
                it.position >= 0L && it.position <= 278_000L
            }, "accepted=${outcomes.count { it.accepted }}"),
            check("mirror-boundary-clips", outcomes.count { it.clip == "LOW" } > 0 &&
                outcomes.count { it.clip == "HIGH" } > 0 && outcomes.count { it.clip == "GAP" } > 0,
                "low=${outcomes.count { it.clip == "LOW" }} high=${outcomes.count { it.clip == "HIGH" }} " +
                    "gap=${outcomes.count { it.clip == "GAP" }}"),
            check("mirror-empty-fail-closed",
                outcomes.takeLast(positions.size).all { !it.accepted && it.position == -1L },
                "rejected=${outcomes.count { !it.accepted }}"),
            check("mirror-back-exact", outcomes.any { it.clip == "NONE" },
                "unclipped=${outcomes.count { it.clip == "NONE" }}"),
            check("mirror-bounded-output", artifact(outcomes, states).toByteArray(Charsets.UTF_8).size <= 1024,
                "bytes=${artifact(outcomes, states).toByteArray(Charsets.UTF_8).size}"),
        )
        val lines = checks + listOf(
            "DIGEST\t${digest()}",
            "SUMMARY\tpass=${checks.count { it.startsWith("CHECK\tPASS") }}/${checks.size}",
        )
        val marker = secret()
        val safe = lines.none { it.contains("://") || it.contains("pin=") || it.contains("Bearer ") ||
            it.contains("token=") || it.contains(marker) }
        return lines + "CHECK\t${if (safe) "PASS" else "FAIL"}\tmirror-diagnostics-secret-free\tlines=${lines.size}"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = reportLines()
        lines.forEach(::println)
        val failed = lines.count { it.startsWith("CHECK\tFAIL") }
        if (failed > 0) exitProcess(1)
    }
}

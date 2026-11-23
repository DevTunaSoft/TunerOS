package com.devtunasoft.tuneros

import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/**
 * QA-10 zero-dependency instrumented mirror of the host event-log gate. It exercises the same
 * bounded event-line semantics as OnDeviceSelfTest using only synthetic in-memory lines: one
 * strict parser, latest-result-wins aggregation, failure-mask decoding, bounded share output,
 * fail-closed empty/oversized/corrupt logs and detector negative controls. No provider, PIN,
 * credential or recording payload is opened; every marker is derived at run time from the seed.
 * The JVM entry point below lets the same assertions run without a device.
 */
class Qa10EventLogInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread({ runChecks() }, "qa10-event-log").start()
    }

    private fun runChecks() {
        val lines = ArrayList<String>(MAX_LINES + 2)
        var passed = 0
        var total = 0
        try {
            for (result in Qa10EventLogAssertions.run()) {
                if (lines.size >= MAX_LINES) break
                total++
                val parts = result.split('\t', limit = 3)
                val ok = parts.getOrNull(0) == "PASS"
                if (ok) passed++
                lines.add(result)
            }
        } catch (error: Throwable) {
            total++
            lines.add("FAIL\tunexpected_exception\t${error.javaClass.simpleName}")
        } finally {
            val summary = "SUMMARY\t$passed/$total\t${if (total > 0 && passed == total) "PASS" else "FAIL"}"
            val report = (lines + summary).joinToString("\n") + "\n"
            File(targetContext.filesDir, "qa10-event-log-report.txt").writeText(report)
            val results = Bundle()
            results.putString("qa10.summary", "$passed/$total")
            finish(if (total > 0 && passed == total) 0 else 1, results)
        }
    }

    private companion object {
        const val MAX_LINES = 24
    }
}

object Qa10EventLogAssertions {
    private enum class S { PASS, FAIL, BLOCKED, NOT_RUN }
    private data class Event(val name: String, val metric: Long)
    private data class Probe(val state: S, val mask: Long, val count: Long, val decoded: String, val detail: String = "")
    private data class Markers(val token: String, val pin: String, val payload: String, val name: String, val url: String)

    private const val SEED = "QA10-FIXED-SEED-2026-11-23"
    private const val HOST = "qa10-provider.invalid"
    private const val MAX_EVENTS_BYTES = 32_768L
    private const val MAX_SHARE_EVENTS = 8_192
    private const val MAX_REPORT_BYTES = 8_192
    private const val FOCUS_MASK_TEXT =
        "1=source, 2=search, 4=filter, 8=search-return, 16=next-target, 32=next-focus"
    private const val MAX_LINES = 24

    @JvmStatic
    fun main(args: Array<String>) {
        val results = run()
        results.forEach(::println)
        val passed = results.count { it.startsWith("PASS\t") }
        println("SUMMARY\t$passed/${results.size}\t${if (passed == results.size) "PASS" else "FAIL"}")
        if (passed != results.size) System.exit(1)
    }

    fun run(): List<String> {
        val results = ArrayList<String>(16)
        fun check(name: String, ok: Boolean, detail: String) {
            results.add((if (ok) "PASS\t" else "FAIL\t") + name + "\t" + detail)
        }
        try {
            val m = markers()
            val sensitive = m.token + "|pin=" + m.pin + "|" + m.payload + "|" + m.name + "|" + m.url + "|://|Bearer |token="
            fun clean(value: String): Boolean = sensitive.split('|').none { value.contains(it) && it.isNotEmpty() }

            val failPass = listOf(event("UI_FOCUS_PROBE_FAIL", 3), event("UI_FOCUS_PROBE_PASS", 0))
            val passFail = listOf(event("UI_FOCUS_PROBE_PASS", 0), event("UI_FOCUS_PROBE_FAIL", 19))
            val focusLast = ui(failPass)
            check("latest_result_wins", focusLast.state == S.PASS && ui(passFail).state == S.FAIL,
                "cases=2 cap=$MAX_EVENTS_BYTES")
            check("failure_mask_decode", decode(19) == "source,search,next-target" &&
                decode(-1) == "invalid" && decode(64) == "invalid" && decode(63).split(',').size == 6,
                "bits=6")
            check("focus_detail_mask", ui(passFail).detail.contains("mask=19") &&
                ui(passFail).detail.contains(FOCUS_MASK_TEXT), "detail=fixed")

            val rearm = rearm(listOf(event("DVR_REARM_EXACT_COUNT", 4), event("DVR_REARM_BLOCKED_COUNT", 5),
                event("DVR_REARM_FAILURE_COUNT", 6)))
            check("rearm_latest_wins", rearm.state == S.FAIL && rearm.count == 6L, "cases=3")

            val allPass = listOf("PASS", "PASS", "PASS", "PASS")
            val mixed = listOf("PASS", "FAIL", "PASS", "NOT_RUN")
            val blocked = listOf("PASS", "BLOCKED", "NOT_RUN", "PASS")
            check("aggregation", disposition(allPass) == "PASS" && disposition(mixed) == "FAIL" &&
                disposition(blocked) == "INCOMPLETE" && mixed.count { it == "NOT_RUN" } == 1, "cases=3")

            val oversized = "9".repeat(4_096) + "\n"
            val corrupt = "1000\t200\tUI_FOCUS_PROBE_PASS\t0\n"
            val empty = ""
            check("fail_closed", strict(oversized) == null && strict(corrupt) == null &&
                strict(empty) == null && ui(emptyList()).state == S.NOT_RUN, "cases=3")

            val report = "TunerOS device self-test\nAutonomous QA: PASS | 4 PASS / 0 FAIL / 0 BLOCKED / 0 NOT RUN\n"
            val share = report + "\nDevice observations (wall ms, uptime ms, event, numeric value, PSS KiB):\n" +
                failPass.joinToString("") { "${1000L + it.metric}\t${it.metric}\t${it.name}\t${it.metric}\t51200\n" }
            check("share_summary_clean", clean(share) && share.length <= MAX_REPORT_BYTES + MAX_SHARE_EVENTS,
                "bytes=${share.length}")
            check("bounded_observe", {
                var size = 0L
                var resets = 0
                for (index in 1..900) {
                    val line = event("PLAYBACK_STARTED", index.toLong()).render(1_700_000_000_000L + index, 400L + index, 51_200L)
                    val bytes = line.toByteArray(Charsets.UTF_8).size.toLong()
                    if (size + bytes > MAX_EVENTS_BYTES) { size = 0; resets++ }
                    size += bytes
                    if (size > MAX_EVENTS_BYTES) return@check false
                }
                resets > 0
            }(), "cap=$MAX_EVENTS_BYTES")

            val laxAcceptsCorrupt = corrupt.split('\t').size >= 4
            check("negative_controls", laxAcceptsCorrupt && strict(corrupt) == null &&
                !clean(share + " " + m.payload) &&
                ui(failPass.asReversed()).state == S.FAIL && ui(failPass).state == S.PASS,
                "controls=4")
            check("bounded_report_lines", results.size + 3 <= MAX_LINES &&
                share.lineSequence().count() <= 24, "lines=${results.size}")
        } catch (error: Throwable) {
            check("no_unexpected_exception", false, error.javaClass.simpleName)
        }
        return results
    }

    private fun event(name: String, metric: Long): Event = Event(name, metric)

    private fun Event.render(wall: Long, uptime: Long, pss: Long): String =
        "$wall\t$uptime\t$name\t$metric\t$pss\n"

    private fun strict(line: String): Event? {
        val f = line.split('\t')
        if (f.size != 5) return null
        val wall = f[0].toLongOrNull() ?: return null
        val uptime = f[1].toLongOrNull() ?: return null
        val pss = f[4].toLongOrNull() ?: return null
        if (wall < 0 || uptime < 0 || pss < 0 || f[2] !in EVENT_NAMES) return null
        val metric = f[3].toLongOrNull() ?: return null
        return Event(f[2], metric)
    }

    private fun ui(lines: List<Event>): Probe {
        var state = S.NOT_RUN
        var mask = -1L
        for (line in lines) when (line.name) {
            "UI_FOCUS_PROBE_PASS" -> { state = S.PASS; mask = 0 }
            "UI_FOCUS_PROBE_FAIL" -> { state = S.FAIL; mask = line.metric }
        }
        return Probe(state, mask, -1, if (state == S.FAIL) decode(mask) else "-",
            if (state == S.FAIL) "D-pad focus probe failed (mask=$mask; $FOCUS_MASK_TEXT)" else "focus=$state")
    }

    private fun rearm(lines: List<Event>): Probe {
        var result: Probe? = null
        for (line in lines) result = when (line.name) {
            "DVR_REARM_EXACT_COUNT" -> Probe(S.PASS, -1, line.metric, "-")
            "DVR_REARM_BLOCKED_COUNT" -> Probe(S.BLOCKED, -1, line.metric, "-")
            "DVR_REARM_FAILURE_COUNT" -> Probe(S.FAIL, -1, line.metric, "-")
            else -> result
        }
        return result ?: Probe(S.NOT_RUN, -1, -1, "-")
    }

    private fun decode(mask: Long): String {
        if (mask < 0) return "invalid"
        val labels = listOf(1L to "source", 2L to "search", 4L to "filter", 8L to "search-return",
            16L to "next-target", 32L to "next-focus")
        var left = mask
        val found = ArrayList<String>(labels.size)
        for ((bit, label) in labels) if (left and bit != 0L) { found.add(label); left = left and bit.inv() }
        return if (left != 0L || found.isEmpty()) "invalid" else found.joinToString(",")
    }

    private fun disposition(states: List<String>): String = when {
        "FAIL" in states -> "FAIL"
        "BLOCKED" in states || "NOT_RUN" in states -> "INCOMPLETE"
        else -> "PASS"
    }

    private fun markers(): Markers {
        val hash = sha256("$SEED|qa10-marker-v1")
        val token = "SYNTHQA10_" + hash.take(10)
        return Markers(token,
            (hash.substring(10, 18).toLong(16) % 100_000_000L).toString().padStart(8, '0'),
            "SYNTHQA10_REC_" + hash.substring(18, 26),
            "QA Synthetic " + hash.substring(26, 31),
            "https://$HOST/stream?token=$token")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    private val EVENT_NAMES = setOf(
        "PLAYLIST_IMPORTED", "PLAYLIST_IMPORT_FAILED", "PLAYLIST_IMPORT_MILLIS",
        "GUIDE_IMPORTED", "GUIDE_IMPORT_FAILED", "GUIDE_IMPORT_MILLIS",
        "PLAYBACK_REQUESTED", "PLAYBACK_READY", "PLAYBACK_STARTED", "PLAYBACK_ERROR",
        "PLAYBACK_FIRST_FRAME", "PLAYBACK_TIMEOUT",
        "RECORDING_STARTED", "RECORDING_COMPLETED", "RECORDING_PARTIAL", "RECORDING_FAILED",
        "RECORDING_CANCELLED", "RECORDING_RECOVERED", "RECORDING_PLAYBACK_REQUESTED",
        "RECORDING_PLAYBACK_READY", "RECORDING_PLAYBACK_STARTED", "RECORDING_PLAYBACK_FIRST_FRAME",
        "RECORDING_PLAYBACK_ERROR", "RECORDING_PLAYBACK_TIMEOUT",
        "DVR_REARM_EXACT_COUNT", "DVR_REARM_BLOCKED_COUNT", "DVR_REARM_FAILURE_COUNT",
        "DVR_PREPARATIONS_RECOVERED", "DVR_ALARM_DELIVERED", "DVR_SCHEDULED_SERVICE_REQUESTED",
        "DVR_SCHEDULED_SERVICE_ACCEPTED", "UI_FOCUS_PROBE_PASS", "UI_FOCUS_PROBE_FAIL",
        "UI_EMPTY_TIMELINE_PASS", "UI_EMPTY_TIMELINE_FAIL", "UI_CLOCK_MARKER_PASS", "UI_CLOCK_MARKER_FAIL",
    )
}

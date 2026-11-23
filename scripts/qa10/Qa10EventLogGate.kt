package com.devtunasoft.tuneros.qa10

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess

/*
 * QA-10 deterministic, zero-new-dependency host gate for the debug-only OnDeviceSelfTest
 * event log and its bounded shareable summary. No provider URL, credential, PIN or recording
 * payload is a literal here: every synthetic marker is derived from the fixed seed at run time.
 *
 * Audit boundary (nothing here duplicates neighbouring gates):
 *  - QA-09 owns StreamDoctor/GuideDoctor shareable reports and their fixture.
 *  - QA-07 owns PIN and bounded favourites lists.
 *  - App code owns the production implementation; this gate never edits it. QA-10 owns only
 *    the event-line aggregation contract, fail-closed parsing, bounded share output, byte-exact
 *    scratch fixture, file-level RED mutants and the instrumented mirror.
 */

private const val VERSION = "qa10-fixture-v1"
private const val SEED = "QA10-FIXED-SEED-2026-11-23"
private const val HOST = "qa10-provider.invalid"
private const val MAX_EVENTS_BYTES = 32_768L
private const val MAX_SHARE_EVENTS = 8_192
private const val MAX_REPORT_BYTES = 8_192
private const val MAX_OUTPUT_LINES = 256
private const val MAX_REPORT_LINES = 12

private const val CONTRACT_TEXT =
    "QA-10 synthetic scratch fixture for the OnDeviceSelfTest event-log gate.\n" +
        "Every marker is derived at run time; no real provider, PIN, credential or recording payload.\n" +
        "Event rows are wall, uptime, event, metric, pss; logs are read in file order.\n"

private val DATA_FILES = listOf(
    "contract.txt", "dimensions.tsv", "logs.tsv", "expect.tsv", "reports.tsv",
    "channels.tsv", "empty.events", "corrupt.events", "oversized.events",
)
private val ALL_FILES = DATA_FILES + "manifest.sha256"

private val LOGS_HEADER = "case\tseq\twall\tuptime\tevent\tmetric\tpss"
private val EXPECT_HEADER = "case\tprobe\tstate\tmask\tcount\tdecoded"
private val REPORTS_HEADER = "case\tstate\tid\tdetail"
private val CHANNELS_HEADER = "id\tname\turl\ttoken\tpin\tpayload"

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
    "UI_EMPTY_TIMELINE_PASS", "UI_EMPTY_TIMELINE_FAIL", "UI_CLOCK_MARKER_PASS",
    "UI_CLOCK_MARKER_FAIL",
)

private val MASK_LABELS = listOf(
    1L to "source", 2L to "search", 4L to "filter", 8L to "search-return",
    16L to "next-target", 32L to "next-focus",
)
private const val FOCUS_MASK_TEXT =
    "1=source, 2=search, 4=filter, 8=search-return, 16=next-target, 32=next-focus"

private class GateFailure(val code: String, detail: String) : RuntimeException(detail)
private fun fail(code: String, detail: String): Nothing = throw GateFailure(code, detail)

private data class Markers(val token: String, val pin: String, val payload: String, val name: String, val url: String) {
    val all: List<String> get() = listOf(token, "pin=$pin", payload, name, url, HOST, "://", "Bearer ", "token=", "api_key=", pin)
}

private data class LogRow(val case: String, val seq: Int, val wall: Long, val uptime: Long, val event: String, val metric: String, val pss: Long) {
    fun render(): String = "$case\t$seq\t$wall\t$uptime\t$event\t$metric\t$pss\n"
    fun eventLine(): String = "$wall\t$uptime\t$event\t$metric\t$pss\n"
}

private data class ExpectRow(val case: String, val probe: String, val state: String, val mask: Long, val count: Long, val decoded: String)
private data class ReportRow(val case: String, val state: String, val id: String, val detail: String)
private data class ChannelRow(val id: String, val name: String, val url: String, val token: String, val pin: String, val payload: String)
private data class EventLine(val wall: Long, val uptime: Long, val event: String, val metric: Long, val pss: Long) {
    fun render(): String = "$wall\t$uptime\t$event\t$metric\t$pss\n"
}

private enum class S { PASS, FAIL, BLOCKED, NOT_RUN }
private data class ProbeResult(val state: S, val mask: Long, val count: Long, val decoded: String, val detail: String)
private data class Report(val disposition: String, val passed: Int, val failed: Int, val blocked: Int, val notRun: Int, val text: String)

private data class Fixture(
    val files: Map<String, ByteArray>,
    val dims: Map<String, String>,
    val logs: List<LogRow>,
    val expects: List<ExpectRow>,
    val reports: List<ReportRow>,
    val channels: List<ChannelRow>,
    val oversizeBytes: ByteArray,
)

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
private fun text(data: ByteArray): String = String(data, Charsets.UTF_8)

private fun markers(): Markers {
    val hash = sha256(bytes("$SEED|qa10-marker-v1"))
    return Markers(
        token = "SYNTHQA10_" + hash.take(10),
        pin = ((hash.substring(10, 18).toLong(16) % 100_000_000L).toString()).padStart(8, '0'),
        payload = "SYNTHQA10_REC_" + hash.substring(18, 26),
        name = "QA Synthetic " + hash.substring(26, 31),
        url = "https://$HOST/stream?token=" + "SYNTHQA10_" + hash.take(10),
    )
}

private fun logRows(): List<LogRow> {
    val rows = ArrayList<LogRow>(256)
    var wall = 1_700_000_000_000L
    fun add(case: String, event: String, metric: String, uptime: Long) {
        rows.add(LogRow(case, rows.count { it.case == case } + 1, wall++, uptime, event, metric, 51_200L))
    }
    add("focus_pass_last", "UI_FOCUS_PROBE_FAIL", "3", 100)
    add("focus_pass_last", "UI_FOCUS_PROBE_PASS", "0", 101)
    add("focus_fail_last", "UI_FOCUS_PROBE_PASS", "0", 102)
    add("focus_fail_last", "UI_FOCUS_PROBE_FAIL", "19", 103)
    add("focus_invalid_mask", "UI_FOCUS_PROBE_FAIL", "BAD", 104)
    add("focus_unrelated_after", "UI_FOCUS_PROBE_FAIL", "3", 105)
    add("focus_unrelated_after", "PLAYBACK_READY", "0", 106)
    add("empty_pass_last", "UI_EMPTY_TIMELINE_FAIL", "0", 107)
    add("empty_pass_last", "UI_EMPTY_TIMELINE_PASS", "0", 108)
    add("empty_fail_last", "UI_EMPTY_TIMELINE_PASS", "0", 109)
    add("empty_fail_last", "UI_EMPTY_TIMELINE_FAIL", "0", 110)
    add("marker_pass_last", "UI_CLOCK_MARKER_FAIL", "0", 111)
    add("marker_pass_last", "UI_CLOCK_MARKER_PASS", "0", 112)
    add("marker_fail_last", "UI_CLOCK_MARKER_PASS", "0", 113)
    add("marker_fail_last", "UI_CLOCK_MARKER_FAIL", "0", 114)
    add("rearm_exact_last", "DVR_REARM_FAILURE_COUNT", "1", 115)
    add("rearm_exact_last", "DVR_REARM_BLOCKED_COUNT", "2", 116)
    add("rearm_exact_last", "DVR_REARM_EXACT_COUNT", "3", 117)
    add("rearm_blocked_last", "DVR_REARM_EXACT_COUNT", "4", 118)
    add("rearm_blocked_last", "DVR_REARM_BLOCKED_COUNT", "5", 119)
    add("rearm_fail_last", "DVR_REARM_BLOCKED_COUNT", "6", 120)
    add("rearm_fail_last", "DVR_REARM_FAILURE_COUNT", "7", 121)
    add("rearm_unrelated_first", "PLAYBACK_STARTED", "0", 122)
    add("rearm_unrelated_first", "DVR_REARM_EXACT_COUNT", "2", 123)
    for (index in 1..220) add("share_large", "PLAYBACK_STARTED", index.toString(), (200 + index).toLong())
    return rows
}

private fun expectRows(): List<ExpectRow> = listOf(
    ExpectRow("focus_pass_last", "focus", "PASS", 0, -1, "-"),
    ExpectRow("focus_fail_last", "focus", "FAIL", 19, -1, "source,search,next-target"),
    ExpectRow("focus_invalid_mask", "focus", "FAIL", -1, -1, "invalid"),
    ExpectRow("focus_unrelated_after", "focus", "FAIL", 3, -1, "source,search"),
    ExpectRow("empty_pass_last", "empty", "PASS", -1, -1, "-"),
    ExpectRow("empty_fail_last", "empty", "FAIL", -1, -1, "-"),
    ExpectRow("marker_pass_last", "marker", "PASS", -1, -1, "-"),
    ExpectRow("marker_fail_last", "marker", "FAIL", -1, -1, "-"),
    ExpectRow("rearm_exact_last", "rearm", "PASS", -1, 3, "-"),
    ExpectRow("rearm_blocked_last", "rearm", "BLOCKED", -1, 5, "-"),
    ExpectRow("rearm_fail_last", "rearm", "FAIL", -1, 7, "-"),
    ExpectRow("rearm_unrelated_first", "rearm", "PASS", -1, 2, "-"),
)

private fun reportRows(): List<ReportRow> = listOf(
    ReportRow("all_pass", "PASS", "qa-a", "Synthetic check A"),
    ReportRow("all_pass", "PASS", "qa-b", "Synthetic check B"),
    ReportRow("all_pass", "PASS", "qa-c", "Synthetic check C"),
    ReportRow("all_pass", "PASS", "qa-d", "Synthetic check D"),
    ReportRow("one_fail", "PASS", "qa-a", "Synthetic check A"),
    ReportRow("one_fail", "FAIL", "qa-b", "Synthetic check B"),
    ReportRow("one_fail", "PASS", "qa-c", "Synthetic check C"),
    ReportRow("one_fail", "NOT_RUN", "qa-d", "Synthetic check D"),
    ReportRow("blocked_only", "PASS", "qa-a", "Synthetic check A"),
    ReportRow("blocked_only", "BLOCKED", "qa-b", "Synthetic check B"),
    ReportRow("blocked_only", "NOT_RUN", "qa-c", "Synthetic check C"),
    ReportRow("blocked_only", "PASS", "qa-d", "Synthetic check D"),
    ReportRow("long_detail", "PASS", "qa-long", "S".repeat(7_600)),
    ReportRow("too_long", "PASS", "qa-huge", "S".repeat(9_000)),
)

private fun channelRows(m: Markers): List<ChannelRow> = (1..3).map { index ->
    ChannelRow("QC00$index", m.name, m.url, m.token, m.pin, m.payload)
}

private fun oversizedBytes(): ByteArray = buildString {
    for (index in 1..140) {
        append(1_700_000_100_000L + index).append('\t').append(300 + index).append('\t')
        append("PLAYBACK_STARTED").append('\t').append("9".repeat(300)).append('\t')
        append(51_200L + index).append('\n')
    }
}.let { bytes(it) }

private fun buildFiles(): Map<String, ByteArray> {
    val m = markers()
    val logs = logRows()
    val dims = LinkedHashMap<String, String>()
    dims["version"] = VERSION
    dims["max_events_bytes"] = MAX_EVENTS_BYTES.toString()
    dims["max_share_events"] = MAX_SHARE_EVENTS.toString()
    dims["max_report_bytes"] = MAX_REPORT_BYTES.toString()
    dims["max_output_lines"] = MAX_OUTPUT_LINES.toString()
    dims["oversize_lines"] = "140"
    dims["oversize_metric_digits"] = "300"
    dims["observe_write_lines"] = "900"
    dims["log_cases"] = logs.map { it.case }.toSet().size.toString()
    dims["log_rows"] = logs.size.toString()
    dims["share_log_rows"] = logs.count { it.case == "share_large" }.toString()
    dims["expect_cases"] = expectRows().size.toString()
    dims["report_cases"] = reportRows().map { it.case }.toSet().size.toString()
    dims["channel_rows"] = channelRows(m).size.toString()

    val files = LinkedHashMap<String, ByteArray>()
    files["contract.txt"] = bytes(CONTRACT_TEXT)
    files["dimensions.tsv"] = bytes(dims.entries.joinToString("") { "${it.key}=${it.value}\n" })
    files["logs.tsv"] = bytes(LOGS_HEADER + "\n" + logs.joinToString("") { it.render() })
    files["expect.tsv"] = bytes(EXPECT_HEADER + "\n" + expectRows().joinToString("") {
        "${it.case}\t${it.probe}\t${it.state}\t${it.mask}\t${it.count}\t${it.decoded}\n"
    })
    files["reports.tsv"] = bytes(REPORTS_HEADER + "\n" + reportRows().joinToString("") {
        "${it.case}\t${it.state}\t${it.id}\t${it.detail}\n"
    })
    files["channels.tsv"] = bytes(CHANNELS_HEADER + "\n" + channelRows(m).joinToString("") {
        "${it.id}\t${it.name}\t${it.url}\t${it.token}\t${it.pin}\t${it.payload}\n"
    })
    files["empty.events"] = ByteArray(0)
    files["corrupt.events"] = bytes(
        "1000\t200\tUI_FOCUS_PROBE_PASS\t0\n" +
            "not-a-number\t200\tUI_FOCUS_PROBE_PASS\t0\t100\n" +
            "1001\t201\t${m.name}\t0\t100\n",
    )
    files["oversized.events"] = oversizedBytes()
    files["manifest.sha256"] = bytes(DATA_FILES.joinToString("") { "${sha256(files.getValue(it))}  $it\n" })
    return files
}

private fun writeAll(dir: File, files: Map<String, ByteArray>) {
    if (!dir.isDirectory && !dir.mkdirs()) fail("IO", "fixture-directory")
    for (name in ALL_FILES) File(dir, name).writeBytes(files.getValue(name))
}

private fun readCommitted(dir: File): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    for (name in ALL_FILES) {
        val file = File(dir, name)
        if (!file.isFile) fail("STRICT_FIELDS", "missing=$name")
        result[name] = file.readBytes()
    }
    return result
}

private fun parseDimensions(raw: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (line in raw.removeSuffix("\n").split('\n')) {
        val parts = line.split('=', limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty() || result.containsKey(parts[0])) {
            fail("STRICT_FIELDS", "dimensions-row")
        }
        result[parts[0]] = parts[1]
    }
    val expected = setOf(
        "version", "max_events_bytes", "max_share_events", "max_report_bytes", "max_output_lines",
        "oversize_lines", "oversize_metric_digits", "observe_write_lines", "log_cases", "log_rows",
        "share_log_rows", "expect_cases", "report_cases", "channel_rows",
    )
    if (result.keys != expected || result["version"] != VERSION) fail("STRICT_FIELDS", "dimensions-keys")
    for (key in listOf("max_events_bytes", "max_share_events", "max_report_bytes", "max_output_lines",
        "oversize_lines", "oversize_metric_digits", "observe_write_lines", "log_cases", "log_rows",
        "share_log_rows", "expect_cases", "report_cases", "channel_rows")) {
        if (result.getValue(key).toLongOrNull() == null) fail("STRICT_FIELDS", "dimensions-number")
    }
    return result
}

private fun rows(raw: String, header: String, columns: Int): List<List<String>> {
    val lines = raw.removeSuffix("\n").split('\n')
    if (lines.isEmpty() || lines[0] != header) fail("STRICT_FIELDS", "tsv-header")
    return lines.drop(1).map { line ->
        val fields = line.split('\t')
        if (fields.size != columns || fields.any { it.isEmpty() }) fail("STRICT_FIELDS", "tsv-fields")
        fields
    }
}

private fun parseEventLine(line: String): EventLine? {
    val f = line.removeSuffix("\n").split('\t')
    if (f.size != 5) return null
    val wall = f[0].toLongOrNull() ?: return null
    val uptime = f[1].toLongOrNull() ?: return null
    if (f[2] !in EVENT_NAMES) return null
    val metric = if (f[3] == "BAD") -1L else f[3].toLongOrNull() ?: return null
    val pss = f[4].toLongOrNull() ?: return null
    if (wall < 0 || uptime < 0 || pss < 0) return null
    return EventLine(wall, uptime, f[2], metric, pss)
}

private fun parseFixtureDir(dir: File): Fixture {
    val committed = readCommitted(dir)
    for (data in committed.values) if (data.any { it == '\r'.code.toByte() }) fail("STRICT_FIELDS", "cr-byte")

    val manifestLines = text(committed.getValue("manifest.sha256")).removeSuffix("\n").split('\n')
    if (manifestLines.size != DATA_FILES.size) fail("MANIFEST_HASH", "manifest-size")
    manifestLines.forEachIndexed { index, line ->
        val parts = line.split("  ", limit = 2)
        if (parts.size != 2 || !Regex("[0-9a-f]{64}").matches(parts[0]) || parts[1] != DATA_FILES[index]) {
            fail("MANIFEST_HASH", "manifest-row")
        }
        if (parts[0] != sha256(committed.getValue(parts[1]))) fail("MANIFEST_HASH", "manifest-digest")
    }

    val dims = parseDimensions(text(committed.getValue("dimensions.tsv")))
    val logs = rows(text(committed.getValue("logs.tsv")), LOGS_HEADER, 7).mapIndexed { index, f ->
        if (f[1].toIntOrNull() == null || f[2].toLongOrNull() == null || f[3].toLongOrNull() == null ||
            f[6].toLongOrNull() == null || f[4] !in EVENT_NAMES ||
            (f[5] != "BAD" && f[5].toLongOrNull() == null)) fail("STRICT_FIELDS", "logs-row=$index")
        LogRow(f[0], f[1].toInt(), f[2].toLong(), f[3].toLong(), f[4], f[5], f[6].toLong())
    }
    for (row in logs) if (parseEventLine(row.eventLine()) == null) {
        fail("STRICT_FIELDS", "logs-event-line")
    }
    val expects = rows(text(committed.getValue("expect.tsv")), EXPECT_HEADER, 6).map { f ->
        if (f[3].toLongOrNull() == null || f[4] == "-" || f[4].toLongOrNull() == null ||
            f[2] !in setOf("PASS", "FAIL", "BLOCKED", "NOT_RUN")) fail("STRICT_FIELDS", "expect-row")
        ExpectRow(f[0], f[1], f[2], f[3].toLong(), f[4].toLong(), f[5])
    }
    val reports = rows(text(committed.getValue("reports.tsv")), REPORTS_HEADER, 4).map { f ->
        if (f[1] !in setOf("PASS", "FAIL", "BLOCKED", "NOT_RUN")) fail("STRICT_FIELDS", "reports-row")
        ReportRow(f[0], f[1], f[2], f[3])
    }
    val channels = rows(text(committed.getValue("channels.tsv")), CHANNELS_HEADER, 6).map { f ->
        ChannelRow(f[0], f[1], f[2], f[3], f[4], f[5])
    }
    if (dims.getValue("log_cases").toLong() != logs.map { it.case }.toSet().size.toLong() ||
        dims.getValue("log_rows").toLong() != logs.size.toLong() ||
        dims.getValue("share_log_rows").toLong() != logs.count { it.case == "share_large" }.toLong() ||
        dims.getValue("expect_cases").toLong() != expects.size.toLong() ||
        dims.getValue("report_cases").toLong() != reports.map { it.case }.toSet().size.toLong() ||
        dims.getValue("channel_rows").toLong() != channels.size.toLong() ||
        dims.getValue("max_events_bytes").toLong() != MAX_EVENTS_BYTES ||
        dims.getValue("max_share_events").toLong() != MAX_SHARE_EVENTS.toLong() ||
        dims.getValue("max_report_bytes").toLong() != MAX_REPORT_BYTES.toLong() ||
        dims.getValue("max_output_lines").toLong() != MAX_OUTPUT_LINES.toLong()) {
        fail("STRICT_FIELDS", "dimension-mismatch")
    }
    return Fixture(committed, dims, logs, expects, reports, channels, committed.getValue("oversized.events"))
}

private fun decodeMask(mask: Long): String {
    if (mask < 0) return "invalid"
    var left = mask
    val labels = ArrayList<String>(MASK_LABELS.size)
    for ((bit, label) in MASK_LABELS) {
        if (left and bit != 0L) {
            labels.add(label)
            left = left and bit.inv()
        }
    }
    if (left != 0L || labels.isEmpty()) return "invalid"
    return labels.joinToString(",")
}

private fun uiResult(caseRows: List<EventLine>): Map<String, ProbeResult> {
    var focus = S.NOT_RUN
    var focusMask = -1L
    var empty = S.NOT_RUN
    var marker = S.NOT_RUN
    for (line in caseRows) {
        when (line.event) {
            "UI_FOCUS_PROBE_PASS" -> { focus = S.PASS; focusMask = 0 }
            "UI_FOCUS_PROBE_FAIL" -> { focus = S.FAIL; focusMask = line.metric }
            "UI_EMPTY_TIMELINE_PASS" -> empty = S.PASS
            "UI_EMPTY_TIMELINE_FAIL" -> empty = S.FAIL
            "UI_CLOCK_MARKER_PASS" -> marker = S.PASS
            "UI_CLOCK_MARKER_FAIL" -> marker = S.FAIL
        }
    }
    val focusDetail = when (focus) {
        S.PASS -> "Actual controls accepted focus traversal"
        S.FAIL -> "D-pad focus probe failed (mask=$focusMask; $FOCUS_MASK_TEXT)"
        else -> "No control focus probe observed"
    }
    return mapOf(
        "focus" to ProbeResult(focus, focusMask, -1, if (focus == S.FAIL) decodeMask(focusMask) else "-", focusDetail),
        "empty" to ProbeResult(empty, -1, -1, "-",
            when (empty) {
                S.PASS -> "Empty EPG View accepted D-pad keys without playback"
                S.FAIL -> "Empty guide lost navigation or attempted playback"
                else -> "No empty-guide D-pad probe observed"
            }),
        "marker" to ProbeResult(marker, -1, -1, "-",
            when (marker) {
                S.PASS -> "Canvas now-marker moved/cleared without rebinding EPG"
                S.FAIL -> "Synthetic clock-marker redraw failed"
                else -> "No clock-marker drawing probe observed"
            }),
    )
}

private fun rearmResult(caseRows: List<EventLine>): ProbeResult {
    var result: ProbeResult? = null
    for (line in caseRows) {
        result = when (line.event) {
            "DVR_REARM_EXACT_COUNT" -> ProbeResult(S.PASS, -1, line.metric, "-",
                "${line.metric} pending exact alarm(s) submitted; firing not yet verified")
            "DVR_REARM_BLOCKED_COUNT" -> ProbeResult(S.BLOCKED, -1, line.metric, "-",
                "${line.metric} pending alarm(s) could not be armed without OS permission")
            "DVR_REARM_FAILURE_COUNT" -> ProbeResult(S.FAIL, -1, line.metric, "-",
                "${line.metric} alarm re-arm request(s) raised errors")
            else -> result
        }
    }
    return result ?: ProbeResult(S.NOT_RUN, -1, -1, "-", "No pending DVR schedules re-armed")
}

private fun buildReport(rows: List<ReportRow>): Report {
    val passed = rows.count { it.state == "PASS" }
    val failed = rows.count { it.state == "FAIL" }
    val blocked = rows.count { it.state == "BLOCKED" }
    val notRun = rows.count { it.state == "NOT_RUN" }
    val disposition = when {
        failed > 0 -> "FAIL"
        blocked > 0 || notRun > 0 -> "INCOMPLETE"
        else -> "PASS"
    }
    val summary = "Autonomous QA: $disposition | $passed PASS / $failed FAIL / $blocked BLOCKED / $notRun NOT RUN"
    val full = buildString {
        appendLine("TunerOS device self-test (no private URLs or provider headers)")
        appendLine("Wall clock (epoch ms): 1700000000000")
        appendLine("Elapsed since boot (ms): 1000")
        appendLine("Android API: 35")
        appendLine("Process PSS snapshot (KiB): 51200 (not a memory benchmark)")
        appendLine(summary)
        for (row in rows) appendLine("${row.state}\t${row.id}\t${row.detail}")
    }
    val published = if (full.length <= MAX_REPORT_BYTES) full else "On-device self-test has not completed yet."
    return Report(disposition, passed, failed, blocked, notRun, published)
}

private fun shareText(report: Report, logBytes: ByteArray): String {
    val events = if (logBytes.size > MAX_EVENTS_BYTES) "None." else {
        val lines = text(logBytes).removeSuffix("\n").split('\n').filter { it.isNotEmpty() }
        val allValid = lines.all { parseEventLine(it) != null }
        if (!allValid) "None." else text(logBytes).takeLast(MAX_SHARE_EVENTS)
    }
    return report.text + "\nDevice observations (wall ms, uptime ms, event, numeric value, PSS KiB):\n" + events
}

private fun clean(m: Markers, value: String): Boolean = m.all.none { value.contains(it) }

private fun boundedObserve(): Pair<Boolean, String> {
    val written = ArrayList<EventLine>(900)
    var size = 0L
    var resets = 0
    var maxSize = 0L
    var wall = 1_700_100_000_000L
    for (index in 1..900) {
        val line = EventLine(wall++, 400L + index, "PLAYBACK_STARTED", index.toLong(), 51_200L).render()
        val bytes = bytes(line).size.toLong()
        if (size + bytes > MAX_EVENTS_BYTES) {
            size = 0
            resets++
            written.clear()
        }
        written.add(parseEventLine(line)!!)
        size += bytes
        if (size > MAX_EVENTS_BYTES) return false to "cap"
        maxSize = maxOf(maxSize, size)
    }
    val ok = resets > 0 && written.isNotEmpty() && written.last().wall == 1_700_100_000_899L &&
        written.all { it.wall > 1_700_100_000_000L } && maxSize <= MAX_EVENTS_BYTES
    return ok to "resets=$resets max=$maxSize kept=${written.size}"
}

private fun verify(dir: File) {
    val checks = ArrayList<String>()
    fun ok(id: String, condition: Boolean, detail: String) {
        if (!condition) fail(id, detail)
        checks.add("CHECK\tPASS\t$id\t$detail")
    }

    val f = parseFixtureDir(dir)
    val m = markers()
    val regenerated = buildFiles()

    ok("MANIFEST_HASH", true, "files=${DATA_FILES.size}")
    ok("STRICT_FIELDS", true, "rows=${f.logs.size + f.expects.size + f.reports.size + f.channels.size}")

    val reportsByCase = f.reports.groupBy { it.case }.mapValues { buildReport(it.value) }
    val shareCases = listOf("all_pass", "one_fail", "blocked_only", "long_detail", "too_long")
    val logsByCase = f.logs.groupBy { it.case }.mapValues { entry ->
        entry.value.mapNotNull { parseEventLine(it.eventLine()) }
    }
    val summaries = shareCases.map { shareText(reportsByCase.getValue(it), f.files.getValue("empty.events")) } +
        logsByCase.keys.map { case -> shareText(reportsByCase.getValue("all_pass"), f.files.getValue(
            if (case == "share_large") "oversized.events" else "empty.events")) }
    val oversizedShare = shareText(reportsByCase.getValue("all_pass"), f.oversizeBytes)
    val corruptShare = shareText(reportsByCase.getValue("all_pass"), f.files.getValue("corrupt.events"))
    val markerRowsOk = f.channels.size == 3 && f.channels.all {
        it.name == m.name && it.url == m.url && it.token == m.token && it.pin == m.pin && it.payload == m.payload
    }
    ok("LEAK_SCAN", markerRowsOk && (summaries + oversizedShare + corruptShare).all { clean(m, it) },
        "cases=${summaries.size + 2} markers=${m.all.size}")

    val longComplete = reportsByCase.getValue("long_detail").text.startsWith("TunerOS device self-test")
    val tooLongFallback = reportsByCase.getValue("too_long").text == "On-device self-test has not completed yet."
    val oversizedExcerpt = shareText(reportsByCase.getValue("all_pass"),
        f.logs.filter { it.case == "share_large" }
            .joinToString("") { it.eventLine() }
            .let { bytes(it) })
    val excerpt = oversizedExcerpt.substringAfter("PSS KiB):\n")
    val bounded = longComplete && tooLongFallback && excerpt.length == MAX_SHARE_EVENTS &&
        oversizedShare.substringAfter("PSS KiB):\n") == "None." &&
        corruptShare.substringAfter("PSS KiB):\n") == "None." &&
        reportsByCase.values.all { it.text.lineSequence().count() <= MAX_REPORT_LINES } &&
        (summaries + oversizedShare + corruptShare + oversizedExcerpt).all {
            it.lineSequence().count() <= MAX_OUTPUT_LINES && it.length <= MAX_REPORT_BYTES + MAX_SHARE_EVENTS + 256
        }
    ok("BOUNDED_OUTPUT", bounded, "excerpt=${excerpt.length} cap=$MAX_SHARE_EVENTS lines<$MAX_OUTPUT_LINES")

    var latestOk = true
    var maskOk = true
    for (expect in f.expects) {
        val lines = logsByCase.getValue(expect.case)
        val result = if (expect.probe == "rearm") rearmResult(lines) else uiResult(lines).getValue(expect.probe)
        latestOk = latestOk && result.state.name == expect.state && result.count == expect.count
        maskOk = maskOk && result.mask == expect.mask && result.decoded == expect.decoded &&
            (expect.probe != "focus" || result.state == S.FAIL || result.detail == "Actual controls accepted focus traversal") &&
            (expect.probe != "focus" || result.state != S.FAIL || result.detail.contains("mask=${expect.mask}") && result.detail.contains(FOCUS_MASK_TEXT))
    }
    ok("LATEST_RESULT_WINS", latestOk && f.expects.size >= 5, "cases=${f.expects.size}")
    ok("AGGREGATION", reportsByCase.getValue("all_pass").disposition == "PASS" &&
        reportsByCase.getValue("all_pass").passed == 4 &&
        reportsByCase.getValue("one_fail").disposition == "FAIL" &&
        reportsByCase.getValue("one_fail").failed == 1 && reportsByCase.getValue("one_fail").notRun == 1 &&
        reportsByCase.getValue("blocked_only").disposition == "INCOMPLETE" &&
        reportsByCase.getValue("blocked_only").blocked == 1 &&
        reportsByCase.getValue("blocked_only").notRun == 1, "cases=${reportsByCase.size}")
    ok("MASK_DECODE", maskOk && decodeMask(19) == "source,search,next-target" && decodeMask(-1) == "invalid" &&
        decodeMask(64) == "invalid" && decodeMask(63).split(',').size == 6, "bits=${MASK_LABELS.size}")

    val emptyLines = logsByCase.getValue("focus_pass_last").take(0)
    val emptyResult = uiResult(emptyLines).getValue("focus").state
    ok("FAIL_CLOSED", emptyResult == S.NOT_RUN &&
        oversizedShare.substringAfter("PSS KiB):\n") == "None." &&
        corruptShare.substringAfter("PSS KiB):\n") == "None." &&
        text(f.files.getValue("corrupt.events")).removeSuffix("\n").split('\n').all { parseEventLine(it) == null } &&
        f.files.getValue("empty.events").isEmpty() && f.oversizeBytes.size > MAX_EVENTS_BYTES,
        "empty=NOT_RUN oversize_bytes=${f.oversizeBytes.size}")

    val (observeOk, observeDetail) = boundedObserve()
    ok("BOUNDED_OBSERVE", observeOk, observeDetail)

    val corrupt = text(f.files.getValue("corrupt.events")).removeSuffix("\n").split('\n')[0]
    val laxAccepts = corrupt.split('\t').size >= 4
    val reversed = logsByCase.getValue("focus_pass_last").asReversed()
    ok("NEGATIVE_CONTROLS", laxAccepts && parseEventLine(corrupt) == null &&
        !clean(m, oversizedShare + "?" + m.payload) &&
        uiResult(reversed).getValue("focus").state == S.FAIL &&
        uiResult(logsByCase.getValue("focus_pass_last")).getValue("focus").state == S.PASS &&
        buildReport(listOf(ReportRow("neg", "PASS", "qa", "S".repeat(MAX_REPORT_BYTES)))).text ==
            "On-device self-test has not completed yet.", "controls=4")

    ok("BYTE_IDENTICAL_REGENERATION", ALL_FILES.all { name ->
        f.files.getValue(name).contentEquals(regenerated.getValue(name))
    }, "files=${ALL_FILES.size}")

    val derived = "DERIVED\tlog_cases=${f.logs.map { it.case }.toSet().size}" +
        "\tlog_rows=${f.logs.size}\tshare_rows=${logsByCase.getValue("share_large").size}" +
        "\treport_cases=${reportsByCase.size}\tchannels=${f.channels.size}" +
        "\toversize_bytes=${f.oversizeBytes.size}\texcerpt=${excerpt.length}" +
        "\texpect_cases=${f.expects.size}"
    for (line in checks) println(line)
    println(derived)
    println("SUMMARY\t${checks.size}/${checks.size}\tPASS")
}

private fun generate(dir: File) {
    val files = buildFiles()
    writeAll(dir, files)
    println("GENERATED\tfiles=${ALL_FILES.size}\tbytes=${ALL_FILES.sumOf { files.getValue(it).size }}")
}

private fun mutate(fixtureDir: File, kind: String, targetDir: File) {
    val files = LinkedHashMap(readCommitted(fixtureDir))
    fun put(name: String, value: String) { files[name] = bytes(value) }
    when (kind) {
        "hash" -> {
            val manifest = text(files.getValue("manifest.sha256"))
            val first = manifest.first()
            val flipped = if (first == '0') '1' else '0'
            put("manifest.sha256", flipped + manifest.drop(1))
        }
        "fields" -> {
            val lines = text(files.getValue("logs.tsv")).removeSuffix("\n").split('\n').toMutableList()
            val parts = lines[1].split('\t').dropLast(1)
            lines[1] = parts.joinToString("\t")
            put("logs.tsv", lines.joinToString("\n") + "\n")
        }
        "leak" -> {
            val marker = markers().token
            val lines = text(files.getValue("reports.tsv")).removeSuffix("\n").split('\n').toMutableList()
            val index = lines.indexOfFirst { it.startsWith("long_detail\t") }
            if (index < 0) fail("IO", "mutant-row")
            lines[index] = lines[index] + "_" + marker
            put("reports.tsv", lines.joinToString("\n") + "\n")
        }
        "bound" -> {
            val lines = text(files.getValue("reports.tsv")).removeSuffix("\n").split('\n').toMutableList()
            val index = lines.indexOfFirst { it.startsWith("long_detail\t") }
            if (index < 0) fail("IO", "mutant-row")
            val fields = lines[index].split('\t')
            lines[index] = listOf(fields[0], fields[1], fields[2], "S".repeat(9_000)).joinToString("\t")
            put("reports.tsv", lines.joinToString("\n") + "\n")
        }
        "latest" -> {
            val lines = text(files.getValue("logs.tsv")).removeSuffix("\n").split('\n').toMutableList()
            val first = lines.indexOfFirst { it.startsWith("focus_pass_last\t") }
            if (first < 0 || !lines[first + 1].startsWith("focus_pass_last\t")) fail("IO", "mutant-rows")
            val tmp = lines[first]
            lines[first] = lines[first + 1]
            lines[first + 1] = tmp
            put("logs.tsv", lines.joinToString("\n") + "\n")
        }
        "bytes" -> {
            val contract = text(files.getValue("contract.txt"))
            put("contract.txt", "XA" + contract.drop(2))
        }
        else -> fail("IO", "unknown-mutant")
    }
    if (kind != "hash") {
        files["manifest.sha256"] = bytes(DATA_FILES.joinToString("") { "${sha256(files.getValue(it))}  $it\n" })
    }
    writeAll(targetDir, files)
    println("MUTATED\tkind=$kind\tfiles=${ALL_FILES.size}")
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: Qa10EventLogGate [generate|verify|mutate] <fixture-dir> [mutant] [target-dir]")
        exitProcess(2)
    }
    try {
        when (args[0]) {
            "generate" -> generate(File(args[1]))
            "verify" -> verify(File(args[1]))
            "mutate" -> {
                if (args.size < 4) fail("IO", "mutate-args")
                mutate(File(args[1]), args[2], File(args[3]))
            }
            else -> fail("IO", "mode")
        }
    } catch (error: GateFailure) {
        println("FIRST\t${error.code}")
        println("DETAIL\t${error.message}")
        println("SUMMARY\tFAIL\t0\t${error.code}")
        exitProcess(1)
    } catch (error: Throwable) {
        println("FIRST\tUNEXPECTED")
        println("DETAIL\t${error.javaClass.simpleName}")
        println("SUMMARY\tFAIL\t0\tUNEXPECTED")
        exitProcess(1)
    }
}

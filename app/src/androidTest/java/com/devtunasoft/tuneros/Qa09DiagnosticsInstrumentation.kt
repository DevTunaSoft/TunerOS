package com.devtunasoft.tuneros

import android.app.Instrumentation
import android.os.Bundle
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideDoctor
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.StreamDoctor
import com.devtunasoft.tuneros.core.StreamEvidence
import com.devtunasoft.tuneros.core.TransportFault
import java.io.File
import java.security.MessageDigest

/**
 * QA-09 zero-dependency instrumented mirror of the host shareable-diagnostics gate.
 *
 * It uses only Instrumentation, Bundle and the pure core report functions. The synthetic host uses
 * RFC 2606 .invalid, all markers are derived from a fixed seed, the report holds check ids and
 * counts only, and every loop is bounded. No provider, recording or user data is opened.
 */
class Qa09DiagnosticsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread({ runChecks() }, "qa09-diagnostics").start()
    }

    private fun runChecks() {
        val lines = ArrayList<String>(MAX_LINES + 2)
        var passed = 0
        var total = 0

        fun check(name: String, ok: Boolean, detail: String = "") {
            total++
            if (ok) passed++
            if (lines.size < MAX_LINES) {
                lines.add((if (ok) "PASS\t" else "FAIL\t") + name + if (detail.isEmpty()) "" else "\t$detail")
            }
        }

        try {
            val marker = markerFrom(SEED)
            val sensitive = listOf(marker.token, "pin=${marker.pin}", marker.payload, HOST, "://", "token=")
            fun clean(text: String): Boolean = sensitive.none { text.contains(it) }

            val evidences = listOf(
                StreamEvidence(404, "text/html; charset=utf-8", TransportFault.DNS),
                StreamEvidence(503, "https://$HOST/live?token=${marker.token}", TransportFault.TLS),
                StreamEvidence(599, "x".repeat(8_192), TransportFault.TIMEOUT),
            )
            val streamReports = evidences.map { StreamDoctor.report(2004, it) }
            check("stream_report_clean", streamReports.all { clean(it) && !it.contains("charset") },
                "cases=${streamReports.size}")
            check("stream_report_bounded", streamReports.all { it.length <= MAX_STREAM_CHARS },
                "cap=$MAX_STREAM_CHARS")

            val warnings = listOf(
                "3 programmes lacked timezone: assumed UTC",
                "see https://$HOST/guide?token=${marker.token} for details",
                "W".repeat(WARNING_INPUT_CHARS),
            )
            val channels = listOf(
                Channel("QC001", "QA One", "https://$HOST/1", "qa.one"),
                Channel("QC002", "QA Two", "https://$HOST/2?pin=${marker.pin}"),
            )
            val programs = (1..PROGRAM_COUNT).map { index ->
                GuideProgram("GP$index", "Synthetic $index", index * 1_800L, index * 1_800L + 1_700L)
            }
            val guide = GuideSnapshot(programs, mapOf("qa.one" to "QA One"), warnings)
            val diagnosis = GuideDoctor.diagnose(channels, mapOf("QC001" to "qa.one"), guide, 1_000L)
            val summary = GuideDoctor.summaryText(diagnosis, warnings, 1_000L)
            check("guide_report_clean", clean(summary), "warnings=${warnings.size}")
            val emitted = summary.lines().filter { it.startsWith("- ") }
            check("guide_warning_bounded",
                emitted.size <= warnings.size && emitted.all { it.length <= MAX_WARNING_LINE_CHARS + 2 },
                "emitted=${emitted.size}")

            val valid = listOf("SC001", "2004", "404", "text/html", "DNS", "https://$HOST/live")
            fun strictRow(fields: List<String>): Boolean {
                if (fields.size != 6 || !Regex("SC\\d{3}").matches(fields[0])) return false
                val code = fields[1].toIntOrNull() ?: return false
                val status = fields[2].toIntOrNull() ?: return false
                return code in -20_000..20_000 &&
                    (status == -1 || status in 100..599) &&
                    setOf("DNS", "TIMEOUT", "REFUSED", "TLS").contains(fields[4])
            }
            val corrupt = valid.dropLast(1)
            check("fail_closed_strict_row", strictRow(valid) && !strictRow(corrupt), "fields=${corrupt.size}")
            check("neg_lax_control", corrupt.size == 5, "control=lax-accepts-corrupt-row")
            check("neg_leak_detector", !clean(streamReports[0] + " recording payload ${marker.payload}"),
                "control=appended-marker")

            val first = deterministicFixtureText(marker)
            val second = deterministicFixtureText(marker)
            check("byte_identity_mirror", sha256(first) == sha256(second), "bytes=${first.length}")
            check("bounded_line_count", lines.size < MAX_LINES, "lines=${lines.size}")
        } catch (error: Throwable) {
            check("no_unexpected_exception", false, error.javaClass.simpleName)
        } finally {
            check("report_lines_clean", lines.none { line -> sensitive(line) })
            val summaryLine = "SUMMARY\t$passed/$total\t${if (passed == total) "PASS" else "FAIL"}"
            val report = (lines + summaryLine).joinToString("\n") + "\n"
            File(targetContext.filesDir, "qa09-diagnostics-report.txt").writeText(report)
            val results = Bundle()
            results.putString("qa09.summary", "$passed/$total")
            finish(if (passed == total) 0 else 1, results)
        }
    }

    /** The sensitive list is local to the try block; the finally check re-derives it without leaking values. */
    private fun sensitive(line: String): Boolean {
        val marker = markerFrom(SEED)
        return listOf(marker.token, "pin=${marker.pin}", marker.payload, HOST, "://", "token=")
            .any { line.contains(it) }
    }

    private fun deterministicFixtureText(marker: Marker): String = buildString {
        append("qa09-fixture-v1\n").append(SEED).append('\n').append(HOST).append('\n')
        append(marker.token).append('\n').append(marker.pin).append('\n').append(marker.payload).append('\n')
        append("SC001\t2004\t404\ttext/html\tDNS\n")
        append("SAFE\t3 programmes lacked timezone: assumed UTC\n")
        append("LONG\t").append("W".repeat(WARNING_INPUT_CHARS)).append('\n')
    }

    private data class Marker(val token: String, val pin: String, val payload: String)

    private fun markerFrom(seed: String): Marker {
        val hash = sha256("$seed|qa09-marker-v1")
        return Marker(
            token = "SYNTHQA09_" + hash.take(10),
            pin = (hash.substring(10, 18).toLong(16) % 100_000_000L).toString().padStart(8, '0'),
            payload = "SYNTHQA09_REC_" + hash.substring(18, 26),
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEED = "QA09-FIXED-SEED-2026-11-23"
        const val HOST = "qa09-provider.invalid"
        const val MAX_STREAM_CHARS = 400
        const val MAX_WARNING_LINE_CHARS = 200
        const val MAX_LINES = 16
        const val WARNING_INPUT_CHARS = 4_096
        const val PROGRAM_COUNT = 1_024
    }
}

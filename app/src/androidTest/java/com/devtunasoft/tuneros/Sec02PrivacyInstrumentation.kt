package com.devtunasoft.tuneros

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Base64
import java.io.File

/**
 * SEC-02 zero-dependency instrumentation. Uses only android.app.Instrumentation,
 * writes a bounded marker-free report and never opens a provider or a recording.
 */
class Sec02PrivacyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread({ runChecks() }, "sec02-privacy-qa").start()
    }

    private fun runChecks() {
        val base = targetContext
        val scratchName = "sec02-qa-" + System.currentTimeMillis()
        val lines = ArrayList<String>()
        var passed = 0
        var total = 0

        fun check(name: String, ok: Boolean, detail: String = "") {
            total++
            if (ok) passed++
            lines.add((if (ok) "PASS\t" else "FAIL\t") + name + if (detail.isEmpty()) "" else "\t$detail")
        }

        val markers = listOf(
            "SYNTH_DEVICE_TOKEN", "SYNTH_DEVICE_UA", "SYNTH_DEVICE_REF",
            "provider.example.invalid", "referer.example.invalid", "://",
        )
        fun clean(text: String): Boolean = markers.none { text.contains(it) }

        val url = "https://provider.example.invalid/live?token=SYNTH_DEVICE_TOKEN"
        val userAgent = "QA-Agent (SYNTH_DEVICE_UA)"
        val referer = "https://referer.example.invalid/?key=SYNTH_DEVICE_REF"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "tuneros_alpha") super.getSharedPreferences(scratchName, mode)
                else super.getSharedPreferences(name, mode)
        }

        try {
            val source = ScheduledRecordingSource(url, userAgent, referer)
            val rendered = source.toString()
            check(
                "source_tostring_redacted",
                clean(rendered) && rendered.contains("urlLen=") && !rendered.contains("url="),
                if (clean(rendered)) "shape" else "marker leak",
            )

            val store = SecureSourceStore(context)
            val saved = ScheduledRecordingSecrets.save(store, "sec02-roundtrip", source)
            val loaded = ScheduledRecordingSecrets.load(store, "sec02-roundtrip")
            val prefs = base.getSharedPreferences(scratchName, Context.MODE_PRIVATE)
            val stored = prefs.all
            check(
                "secure_roundtrip_and_no_plaintext_at_rest",
                saved && loaded == source && stored.containsKey("sec02-roundtrip.secure") &&
                    !stored.containsKey("sec02-roundtrip") && stored.values.all { it is String && clean(it) },
                "saved=$saved loaded=${loaded != null}",
            )

            val corrupt = Base64.encodeToString(ByteArray(13) { 0x41 }, Base64.DEFAULT)
            prefs.edit()
                .putString("sec02-corrupt.secure", corrupt)
                .putString("sec02-corrupt", url)
                .commit()
            val corruptLoad = SecureSourceStore(context).load("sec02-corrupt")
            check("corrupt_protected_record_fails_closed", corruptLoad == null, "returned=${corruptLoad != null}")

            val oversized = "https://provider.example.invalid/" + "A".repeat(100_000) + "?token=SYNTH_DEVICE_TOKEN"
            val oversizeSaved = SecureSourceStore(context).save("sec02-oversize", oversized)
            val afterOversize = base.getSharedPreferences(scratchName, Context.MODE_PRIVATE).all.values
            check(
                "oversized_save_rejected",
                !oversizeSaved && afterOversize.none { it is String && it.length > 11_000 },
                "saved=$oversizeSaved",
            )

            val totalChars = base.getSharedPreferences(scratchName, Context.MODE_PRIVATE).all.values
                .sumOf { if (it is String) it.length else 0 }
            check("bounded_storage_after_normal_saves", totalChars < 40_000, "chars=$totalChars")

            val report = buildString {
                appendLine("SEC02-SHAREABLE-REPORT-v1")
                appendLine("schedules=1")
                appendLine("sources=1")
                appendLine("source=$rendered")
            }.trimEnd()
            check(
                "shareable_report_sanitized",
                clean(report) && report.startsWith("SEC02-SHAREABLE-REPORT-v1") &&
                    report.contains("urlLen=") && !report.contains(url),
                if (clean(report)) "clean" else "marker leak",
            )
        } catch (t: Throwable) {
            check("instrumentation_no_exception", false, t.javaClass.simpleName)
        } finally {
            check("report_lines_contain_no_markers", lines.none { line -> markers.any { line.contains(it) } })
            lines.add("SUMMARY\t$passed/$total\t${if (passed == total) "PASS" else "FAIL"}")
            File(base.filesDir, "sec02-privacy-report.txt").writeText(lines.joinToString("\n") + "\n")
            runCatching { base.deleteSharedPreferences(scratchName) }
            val results = Bundle()
            results.putString("sec02.summary", "$passed/$total")
            finish(if (passed == total) 0 else 1, results)
        }
    }
}

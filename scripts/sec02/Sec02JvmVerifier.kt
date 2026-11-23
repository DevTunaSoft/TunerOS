package com.devtunasoft.tuneros.sec02

import android.content.Context
import android.content.SharedPreferences
import com.devtunasoft.tuneros.ScheduledRecordingSecrets
import com.devtunasoft.tuneros.ScheduledRecordingSource
import com.devtunasoft.tuneros.SecureSourceStore
import sec02qa.FakeAndroidKeyStoreProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.Security

private data class SourceRow(val id: String, val url: String, val userAgent: String, val referer: String)
private data class MetadataRow(val id: String, val title: String, val channelId: String, val lastError: String)
private data class CheckResult(val name: String, val pass: Boolean, val detail: String)

private class FakePrefs : SharedPreferences {
    val map = LinkedHashMap<String, String>()
    var failCommitFor: String? = null

    override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue

    override fun getAll(): MutableMap<String, Any> = LinkedHashMap(map)

    override fun edit(): SharedPreferences.Editor = Editor(this)

    private class Editor(private val owner: FakePrefs) : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, String?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }

        override fun commit(): Boolean {
            val blocked = owner.failCommitFor
            if (blocked != null && pending.containsKey(blocked)) {
                // Simulate one transient disk failure; the cleanup commit must succeed.
                owner.failCommitFor = null
                return false
            }
            for ((key, value) in pending) {
                if (value == null) owner.map.remove(key) else owner.map[key] = value
            }
            return true
        }
    }
}

private class FakeContext(val prefs: FakePrefs) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    override fun deleteSharedPreferences(name: String) {
        prefs.map.clear()
    }
}

private fun readRows(file: File): List<List<String>> =
    file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

private fun sources(file: File): List<SourceRow> = readRows(file).map {
    require(it.size == 4) { "Malformed synthetic-sources.tsv row" }
    SourceRow(it[0], it[1], it[2], it[3])
}

private fun metadata(file: File): List<MetadataRow> = readRows(file).map {
    require(it.size == 4) { "Malformed synthetic-metadata.tsv row" }
    MetadataRow(it[0], it[1], it[2], it[3])
}

private class Audit(private val markers: List<String>) {
    private val results = ArrayList<CheckResult>()

    fun expect(name: String, condition: Boolean, detail: String) {
        results.add(CheckResult(name, condition, detail))
    }

    fun expectClean(name: String, text: String, condition: Boolean = true) {
        val leaks = markers.filter { text.contains(it, ignoreCase = false) }
        expect(name, condition && leaks.isEmpty(), if (leaks.isEmpty()) "no markers" else "marker count=${leaks.size}")
    }

    fun addAll(other: List<CheckResult>) {
        results.addAll(other)
    }

    fun printAll() {
        for (result in results) {
            println("CHECK\t${if (result.pass) "PASS" else "FAIL"}\t${result.name}\t${result.detail}")
        }
        val passed = results.count { it.pass }
        println("SUMMARY\t$passed/${results.size}\t${if (passed == results.size) "PASS" else "FAIL"}")
    }

    fun allPass(): Boolean = results.all { it.pass }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: Sec02PrivacyVerifier <fixturesDir> <productionSourceDir>")
        kotlin.system.exitProcess(2)
    }
    val fixtures = File(args[0])
    val sourceDir = File(args[1])
    val sourceRows = sources(File(fixtures, "synthetic-sources.tsv"))
    val metadataRows = metadata(File(fixtures, "synthetic-metadata.tsv"))
    val markers = File(fixtures, "secret-markers.txt").readLines().filter { it.isNotBlank() }
    val expectedReport = File(fixtures, "expected-shareable-report.txt").readText().trimEnd()
    val audit = Audit(markers)

    // Static audit: the two named classes must never contain a diagnostics sink.
    val productionFiles = listOf(
        File(sourceDir, "ScheduledRecordingSecrets.kt"),
        File(sourceDir, "SecureSourceStore.kt"),
    )
    val sourceText = productionFiles.joinToString("\n") { it.readText() }
    val forbidden = listOf("Log.", "println(", "print(", "System.out", "System.err", "Timber", "printStackTrace")
    val foundSinks = forbidden.filter { sourceText.contains(it) }
    audit.expect("no_diagnostic_sinks_in_production", foundSinks.isEmpty(), "sink count=${foundSinks.size}")

    val allUrls = sourceRows.map { it.url } + metadataRows.map { it.lastError }
    audit.expect(
        "fixtures_synthetic_only",
        sourceRows.all { it.url.contains("example.invalid") && it.referer.contains("example.invalid") } &&
            markers.all { it == "://" || it.startsWith("SYNTH_") || it.endsWith(".invalid") } &&
            allUrls.all { it.contains("example.invalid") || it.startsWith("SYNTH_") },
        "rows=${sourceRows.size} markers=${markers.size}",
    )

    if (Security.getProvider("AndroidKeyStore") != null) {
        Security.removeProvider("AndroidKeyStore")
    }
    Security.addProvider(FakeAndroidKeyStoreProvider())

    val capturedOut = ByteArrayOutputStream()
    val capturedErr = ByteArrayOutputStream()
    val originalOut = System.out
    val originalErr = System.err
    System.setOut(PrintStream(capturedOut, true, "UTF-8"))
    System.setErr(PrintStream(capturedErr, true, "UTF-8"))
    val runtime = ArrayList<CheckResult>()
    try {
        runtime.addAll(runRuntimeChecks(sourceRows, metadataRows, markers, expectedReport))
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }
    audit.addAll(runtime)

    val captured = capturedOut.toString("UTF-8") + "\n" + capturedErr.toString("UTF-8")
    val capturedLeaks = markers.filter { captured.contains(it) }
    val hasScheme = captured.contains("://")
    audit.expect(
        "no_runtime_diagnostic_output",
        capturedLeaks.isEmpty() && !hasScheme,
        "leaks=${capturedLeaks.size} urls=$hasScheme",
    )

    audit.printAll()
    if (!audit.allPass()) kotlin.system.exitProcess(1)
}

private fun runRuntimeChecks(
    sources: List<SourceRow>,
    metadata: List<MetadataRow>,
    markers: List<String>,
    expectedReport: String,
): List<CheckResult> {
    val results = ArrayList<CheckResult>()
    fun check(name: String, condition: Boolean, detail: String) {
        results.add(CheckResult(name, condition, detail))
    }
    fun clean(text: String): Boolean = markers.none { text.contains(it) } && !text.contains("://")

    FakeAndroidKeyStoreProvider.reset()

    // 1. The generated toString() of the secret-bearing data class is a diagnostics surface.
    val first = sources[0]
    val firstSource = ScheduledRecordingSource(first.url, first.userAgent, first.referer)
    val rendered = firstSource.toString()
    val shapeOk = rendered.contains("urlLen=") && !rendered.contains("url=")
    check(
        "source_tostring_redacted",
        clean(rendered) && shapeOk,
        "clean=${clean(rendered)} shape=$shapeOk",
    )

    // 2. Encrypted round trip; no plaintext copy may remain in storage.
    val prefs = FakePrefs()
    val store = SecureSourceStore(FakeContext(prefs))
    val saved = ScheduledRecordingSecrets.save(store, "roundtrip", firstSource)
    val loaded = ScheduledRecordingSecrets.load(store, "roundtrip")
    check(
        "secure_roundtrip_and_no_plaintext_at_rest",
        saved && loaded == firstSource && prefs.map.containsKey("roundtrip.secure") &&
            !prefs.map.containsKey("roundtrip") && prefs.map.values.all(::clean),
        "saved=$saved loaded=${loaded != null} keys=${prefs.map.size}",
    )

    // 3. Corrupt protected record plus a plaintext fallback must NOT disclose the fallback.
    val corruptPrefs = FakePrefs()
    corruptPrefs.map["corrupt.secure"] =
        android.util.Base64.encodeToString(ByteArray(13) { 0x41 }, android.util.Base64.NO_WRAP)
    corruptPrefs.map["corrupt"] = first.url
    val corruptLoad = SecureSourceStore(FakeContext(corruptPrefs)).load("corrupt")
    check("corrupt_protected_record_fails_closed", corruptLoad == null, "returned=${corruptLoad != null}")

    // 4. Corrupt protected record with no fallback is simply unavailable.
    val barePrefs = FakePrefs()
    barePrefs.map["bare.secure"] =
        android.util.Base64.encodeToString(ByteArray(13) { 0x41 }, android.util.Base64.NO_WRAP)
    check("corrupt_without_legacy_fails_closed", SecureSourceStore(FakeContext(barePrefs)).load("bare") == null, "no fallback")

    // 5. Oversized values must be rejected before encryption or storage.
    val oversizePrefs = FakePrefs()
    val oversizedUrl = "https://provider.example.invalid/" + "A".repeat(100_000) + "?token=SYNTH_OVERSIZE"
    val oversizeSaved = SecureSourceStore(FakeContext(oversizePrefs)).save("oversize", oversizedUrl)
    check(
        "oversized_save_rejected",
        !oversizeSaved && oversizePrefs.map.isEmpty(),
        "saved=$oversizeSaved stored=${oversizePrefs.map.size}",
    )

    // 6. Oversized stored ciphertext must be rejected before Base64 decode allocation.
    android.util.Base64.decodeCalls = 0
    android.util.Base64.maxDecodedChars = 0
    val hugePrefs = FakePrefs()
    hugePrefs.map["huge.secure"] = "A".repeat(200_000)
    val hugeLoad = SecureSourceStore(FakeContext(hugePrefs)).load("huge")
    check(
        "oversized_stored_record_not_decoded",
        hugeLoad == null && android.util.Base64.decodeCalls == 0 && android.util.Base64.maxDecodedChars == 0,
        "loaded=${hugeLoad != null} decodes=${android.util.Base64.decodeCalls} maxChars=${android.util.Base64.maxDecodedChars}",
    )

    // 7. Boundaries: the documented maximum is accepted, one over is refused,
    // and the byte cap also applies to multibyte input shorter than the char cap.
    val boundaryPrefs = FakePrefs()
    val boundaryStore = SecureSourceStore(FakeContext(boundaryPrefs))
    val atCharLimit = "A".repeat(4_096)
    val overCharLimit = "A".repeat(4_097)
    val atByteLimit = "\u00e9".repeat(3_000)
    val overByteLimit = "\u00e9".repeat(5_000)
    val boundaryOk = boundaryStore.save("atChar", atCharLimit) &&
        boundaryStore.load("atChar") == atCharLimit &&
        boundaryPrefs.map.getValue("atChar.secure").length <= 11_000 &&
        boundaryStore.save("atByte", atByteLimit) &&
        boundaryStore.load("atByte") == atByteLimit &&
        !boundaryStore.save("overChar", overCharLimit) &&
        !boundaryStore.save("overByte", overByteLimit) &&
        !boundaryPrefs.map.containsKey("overChar.secure") &&
        !boundaryPrefs.map.containsKey("overByte.secure")
    check("secret_size_boundaries", boundaryOk, "accepted=2 rejected=2")

    // 8. Normal saves stay bounded on disk.
    val boundedPrefs = FakePrefs()
    val boundedStore = SecureSourceStore(FakeContext(boundedPrefs))
    sources.forEachIndexed { index, row ->
        ScheduledRecordingSecrets.save(
            boundedStore,
            "bounded$index",
            ScheduledRecordingSource(row.url, row.userAgent, row.referer),
        )
    }
    val storedChars = boundedPrefs.map.values.sumOf { it.length }
    check(
        "bounded_storage_after_normal_saves",
        storedChars < 40_000 && boundedPrefs.map.values.all { it.length <= 11_000 },
        "values=${boundedPrefs.map.size} chars=$storedChars",
    )

    // 9. Deletion clears both encrypted and legacy keys in one commit.
    val deletePrefs = FakePrefs()
    val deleteStore = SecureSourceStore(FakeContext(deletePrefs))
    val second = sources[1]
    ScheduledRecordingSecrets.save(
        deleteStore,
        "delete",
        ScheduledRecordingSource(second.url, second.userAgent, second.referer),
    )
    val deleted = ScheduledRecordingSecrets.delete(deleteStore, "delete")
    check("delete_removes_all_representations", deleted && deletePrefs.map.isEmpty(), "remaining=${deletePrefs.map.size}")

    // 10. A failed intermediate commit must roll the whole source back.
    val partialPrefs = FakePrefs()
    partialPrefs.failCommitFor = "partial.userAgent"
    val partialStore = SecureSourceStore(FakeContext(partialPrefs))
    val third = sources[2]
    val partialSaved = ScheduledRecordingSecrets.save(
        partialStore,
        "partial",
        ScheduledRecordingSource(third.url, third.userAgent, third.referer),
    )
    partialPrefs.failCommitFor = null
    check("partial_save_rolls_back", !partialSaved && partialPrefs.map.isEmpty(), "saved=$partialSaved remaining=${partialPrefs.map.size}")

    // 11. Keystore failure fails closed without writing plaintext.
    val failPrefs = FakePrefs()
    FakeAndroidKeyStoreProvider.failGenerateKey = true
    val failSaved = SecureSourceStore(FakeContext(failPrefs)).save("keystore", first.url)
    FakeAndroidKeyStoreProvider.failGenerateKey = false
    check("keystore_failure_fails_closed", !failSaved && failPrefs.map.isEmpty(), "saved=$failSaved")

    // 12. Tampered ciphertext fails closed.
    val tamperPrefs = FakePrefs()
    val tamperStore = SecureSourceStore(FakeContext(tamperPrefs))
    tamperStore.save("tamper", first.url)
    val encoded = tamperPrefs.map.getValue("tamper.secure")
    val bytes = java.util.Base64.getDecoder().decode(encoded)
    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
    tamperPrefs.map["tamper.secure"] = java.util.Base64.getEncoder().encodeToString(bytes)
    check("tampered_ciphertext_fails_closed", tamperStore.load("tamper") == null, "returned null")

    // 13. Shareable QA report: counts plus the redacted source line only.
    val report = buildString {
        appendLine("SEC02-SHAREABLE-REPORT-v1")
        appendLine("schedules=${metadata.size}")
        appendLine("sources=${sources.size}")
        appendLine("source=$firstSource")
    }.trimEnd()
    val golden = expectedReport.replace("{urlLen}", first.url.length.toString())
    check(
        "shareable_report_matches_golden",
        report == golden,
        if (report == golden) "exact match" else "report shape differs",
    )
    check(
        "shareable_report_has_no_metadata_or_markers",
        clean(report) && metadata.all { !report.contains(it.title) && !report.contains(it.lastError) },
        "clean=${clean(report)}",
    )

    return results
}

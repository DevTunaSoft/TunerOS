package com.devtunasoft.tuneros.qa58

import java.security.MessageDigest
import java.util.Locale

/**
 * QA-58 framework-free contract for bounded watch-history / resume-position persistence.
 * The same source compiles into the host gate and the instrumented mirror. Diagnostics carry
 * only check ids, counts and hashes: raw input values are never echoed.
 */
object Qa58Contract {
    const val HEADER = "QA58RESUME.v1"
    const val MAX_ENTRIES = 64
    const val TOP_LIMIT = 8
    const val WINDOW_MS = 2_592_000_000L
    const val TAIL_MS = 3_000L
    const val ENTRIES = 20_000
    const val CORRUPT_CASES = 7
    const val MAX_LINES = 20_100
    const val BASE_UPDATED_MS = 1_700_000_000_000L

    // sha256 of the generated rows.tsv bytes. Updated only by the deterministic generator.
    const val PINNED_FIXTURE_DIGEST = "b1034f673ae16a9cf1a2737beaa86e83bb56e7f73af65a3cfc196a74784709f6"

    val ALL_FILES = listOf("rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt", "sha256.txt")
    val DATA_FILES = listOf("rows.tsv", "dimensions.tsv", "corrupt.txt", "report-copy.txt")

    private const val SEED = 20261124L
    private val KEY_RE = Regex("[a-z][a-z0-9]{5,7}")
    private val HEX = "0123456789abcdef"

    data class Entry(val key: String, val positionMs: Long, val durationMs: Long, val updatedMs: Long)

    class Result(val ok: Boolean, val code: String, val entries: Map<String, Entry>, val staleDropped: Int)

    fun secretToken(): String {
        val mixed = SEED * 6364136223846793005L + 1442695040888963407L
        val digits = java.lang.Long.toUnsignedString(mixed)
        return if (digits.length >= 8) digits.takeLast(8) else digits.padStart(8, '0')
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder()
        for (b in digest) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return out.toString()
    }

    private fun line(key: String, position: Long, duration: Long, updated: Long): String =
        listOf(key, position.toString(), duration.toString(), updated.toString()).joinToString("\t")

    private fun generatedRows(): String {
        val out = StringBuilder(HEADER).append('\n')
        val tieAt = ENTRIES - TOP_LIMIT
        for (i in 0 until ENTRIES) {
            val key = "ch" + i.toString().padStart(6, '0')
            val position = 1_000L + ((i * 37L) % 90_000L)
            val duration = 100_000L + ((i % 7L) * 1_000L)
            val updated = if (i >= tieAt) {
                BASE_UPDATED_MS + (tieAt * 1_000L)
            } else {
                BASE_UPDATED_MS + (i * 1_000L)
            }
            out.append(line(key, position, duration, updated)).append('\n')
        }
        return out.toString()
    }

    private fun generatedDimensions(): String = listOf(
        "entries\t$ENTRIES",
        "max_entries\t$MAX_ENTRIES",
        "top_limit\t$TOP_LIMIT",
        "window_ms\t$WINDOW_MS",
        "corrupt_cases\t$CORRUPT_CASES",
        "tail_ms\t$TAIL_MS",
    ).joinToString("\n", postfix = "\n")

    private fun generatedCorrupt(): String {
        val h = HEADER
        val k = "ch000001"
        val u = BASE_UPDATED_MS
        return listOf(
            "CASE\theader-bad",
            "ROW\tQA58RESUME.v0",
            "CASE\tfield-extra",
            "ROW\t$h",
            "ROW\t$k\t1000\t100000\t$u\textra",
            "CASE\tfield-short",
            "ROW\t$h",
            "ROW\t$k\t1000\t100000",
            "CASE\tbad-number",
            "ROW\t$h",
            "ROW\t$k\txxxx\t100000\t$u",
            "CASE\tnegative-position",
            "ROW\t$h",
            "ROW\t$k\t-1\t100000\t$u",
            "CASE\ttail-violation",
            "ROW\t$h",
            "ROW\t$k\t99000\t100000\t$u",
            "CASE\tbad-key",
            "ROW\t$h",
            "ROW\tCH000001\t1000\t100000\t$u",
        ).joinToString("\n", postfix = "\n")
    }

    private fun generatedReportCopy(): String = listOf(
        "QA58 bounded diagnostics scratch copy",
        "CHECK\tROUND_TRIP\tPASS",
        "CHECK\tCAP_BINDING\tPASS",
        "CHECK\tCORRUPT_FAIL_CLOSED\tPASS",
        "SUMMARY\tchecks=3\tfail=0",
    ).joinToString("\n", postfix = "\n")

    fun generate(): Map<String, ByteArray> {
        val rows = generatedRows().toByteArray(Charsets.UTF_8)
        val out = LinkedHashMap<String, ByteArray>()
        out["rows.tsv"] = rows
        out["dimensions.tsv"] = generatedDimensions().toByteArray(Charsets.UTF_8)
        out["corrupt.txt"] = generatedCorrupt().toByteArray(Charsets.UTF_8)
        out["report-copy.txt"] = generatedReportCopy().toByteArray(Charsets.UTF_8)
        val manifest = StringBuilder()
        for (name in DATA_FILES) manifest.append(sha256(out.getValue(name))).append('\t').append(name).append('\n')
        out["sha256.txt"] = manifest.toString().toByteArray(Charsets.UTF_8)
        return out
    }

    /** Strict decoder. Never returns raw field text in [code]. */
    fun decode(text: String): Result {
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != HEADER) return Result(false, "HEADER_STRICT", emptyMap(), 0)
        if (lines.size > MAX_LINES + 1) return Result(false, "ROW_BOUNDS", emptyMap(), 0)
        val entries = LinkedHashMap<String, Entry>()
        var stale = 0
        for (raw in lines.drop(1)) {
            val fields = raw.split('\t')
            if (fields.size != 4) return Result(false, "FIELD_COUNT", emptyMap(), 0)
            val key = fields[0]
            if (!KEY_RE.matches(key)) return Result(false, "BAD_KEY", emptyMap(), 0)
            val position = fields[1].toLongOrNull() ?: return Result(false, "BAD_NUMBER", emptyMap(), 0)
            val duration = fields[2].toLongOrNull() ?: return Result(false, "BAD_NUMBER", emptyMap(), 0)
            val updated = fields[3].toLongOrNull() ?: return Result(false, "BAD_NUMBER", emptyMap(), 0)
            if (position < 0L) return Result(false, "NEGATIVE_POSITION", emptyMap(), 0)
            if (duration <= TAIL_MS) return Result(false, "BAD_DURATION", emptyMap(), 0)
            if (position >= duration - TAIL_MS) return Result(false, "TAIL_VIOLATION", emptyMap(), 0)
            if (updated <= 0L) return Result(false, "BAD_TIMESTAMP", emptyMap(), 0)
            val candidate = Entry(key, position, duration, updated)
            val previous = entries[key]
            if (previous == null || updated > previous.updatedMs) {
                entries[key] = candidate
            } else {
                stale++
            }
            if (entries.size > MAX_ENTRIES) {
                var victim = ""
                for (candidateKey in entries.keys) {
                    val current = entries.getValue(candidateKey)
                    if (victim.isEmpty() || current.updatedMs < entries.getValue(victim).updatedMs ||
                        (current.updatedMs == entries.getValue(victim).updatedMs && candidateKey < victim)
                    ) victim = candidateKey
                }
                entries.remove(victim)
            }
        }
        return Result(true, "OK", entries, stale)
    }

    fun serialize(entries: Map<String, Entry>): String {
        val out = StringBuilder(HEADER).append('\n')
        for (entry in entries.values.sortedBy { it.key }) {
            out.append(line(entry.key, entry.positionMs, entry.durationMs, entry.updatedMs)).append('\n')
        }
        return out.toString()
    }

    fun rank(entries: Map<String, Entry>, nowMs: Long): List<String> = entries.values
        .asSequence()
        .filter { nowMs - it.updatedMs in 0L..WINDOW_MS }
        .sortedWith(compareByDescending<Entry> { it.updatedMs }.thenBy { it.key })
        .take(TOP_LIMIT)
        .map { it.key }
        .toList()

    data class CorruptCase(val name: String, val lines: List<String>)

    fun parseCorrupt(text: String): List<CorruptCase>? {
        val cases = ArrayList<CorruptCase>()
        var name = ""
        val rows = ArrayList<String>()
        for (raw in text.split('\n').dropLastWhile { it.isEmpty() }) {
            val fields = raw.split('\t', limit = 2)
            when (fields[0]) {
                "CASE" -> {
                    if (fields.size != 2) return null
                    if (name.isNotEmpty()) {
                        if (rows.isEmpty()) return null
                        cases.add(CorruptCase(name, rows.toList()))
                        rows.clear()
                    }
                    name = fields[1]
                }
                "ROW" -> {
                    if (name.isEmpty() || fields.size != 2) return null
                    rows.add(fields[1])
                }
                else -> return null
            }
        }
        if (name.isEmpty() || rows.isEmpty()) return null
        cases.add(CorruptCase(name, rows.toList()))
        return cases
    }

    fun parseDimensions(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (raw in text.split('\n').dropLastWhile { it.isEmpty() }) {
            val fields = raw.split('\t', limit = 2)
            if (fields.size != 2 || out.containsKey(fields[0])) return null
            out[fields[0]] = fields[1]
        }
        return out
    }

    fun privacyOffense(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("://")) return "URL_SCHEME"
        for (marker in listOf("key=", "key:", "password=", "password:", "passwd=", "secret=", "token=", "pin=", "credential=", "credential:")) {
            if (lower.contains(marker)) return "CREDENTIAL_MARKER"
        }
        if (text.contains(secretToken())) return "DERIVED_SECRET"
        if (lower.contains("payload=") || lower.contains("recording-payload")) return "RECORDING_PAYLOAD"
        return null
    }

    /** Verify committed bytes. Mode skips only the structural checks that would mask a named mutant. */
    fun verifyModel(mode: String, files: Map<String, ByteArray>, emit: (String) -> Unit): Int {
        val emitted = ArrayList<String>()
        fun out(line: String) {
            emitted.add(line)
            emit(line)
        }
        var first = ""
        fun check(code: String, ok: Boolean) {
            out("CHECK\t$code\t" + if (ok) "PASS" else "FAIL")
            if (!ok && first.isEmpty()) {
                first = code
                out("FIRST\t$code")
            }
        }
        val semantic = mode == "verify-semantic"
        val pinnedMode = mode == "verify-pinned"
        val manifestMode = mode == "verify-manifest"
        val threadsBefore = Thread.activeCount()
        try {
            if (!semantic && !pinnedMode && !manifestMode) {
                val regenerated = generate()
                check("BYTE_IDENTITY", ALL_FILES.all { regenerated.getValue(it).contentEquals(files.getValue(it)) })
            }
            if (!semantic && !pinnedMode) {
                val manifestText = String(files.getValue("sha256.txt"), Charsets.UTF_8)
                val lines = manifestText.split('\n').dropLastWhile { it.isEmpty() }
                val parsed = lines.map { it.split('\t', limit = 2) }
                check(
                    "MANIFEST_CONSISTENT",
                    parsed.size == DATA_FILES.size && parsed.zip(DATA_FILES).all { (fields, name) ->
                        fields.size == 2 && fields[1] == name && fields[0] == sha256(files.getValue(name))
                    }
                )
            }
            if (!semantic) {
                check("PINNED_DIGEST", sha256(files.getValue("rows.tsv")) == PINNED_FIXTURE_DIGEST)
            }

            val dimensions = parseDimensions(String(files.getValue("dimensions.tsv"), Charsets.UTF_8))
            check(
                "DIMENSIONS_STRICT",
                dimensions != null && dimensions["entries"] == ENTRIES.toString() &&
                    // Pinned fixture dimension: a mutated MAX_ENTRIES must reach CAP_BINDING, not fail here.
                    dimensions["max_entries"] == "64" &&
                    dimensions["top_limit"] == TOP_LIMIT.toString() &&
                    dimensions["window_ms"] == WINDOW_MS.toString() &&
                    dimensions["corrupt_cases"] == CORRUPT_CASES.toString() &&
                    dimensions["tail_ms"] == TAIL_MS.toString()
            )
            val rowsText = String(files.getValue("rows.tsv"), Charsets.UTF_8)
            check("HEADER_STRICT", rowsText.startsWith(HEADER + "\n"))
            val rawRows = rowsText.split('\n').dropLastWhile { it.isEmpty() }
            val corruptCases = parseCorrupt(String(files.getValue("corrupt.txt"), Charsets.UTF_8))
            check(
                "ROW_BOUNDS",
                rawRows.size == ENTRIES + 1 && corruptCases?.size == CORRUPT_CASES &&
                    rawRows.drop(1).all { it.split('\t').size == 4 }
            )

            val rejected = corruptCases?.all { c ->
                val result = decode(c.lines.joinToString("\n", postfix = "\n"))
                !result.ok && result.code != "OK" && result.code.isNotEmpty()
            } ?: false
            check("CORRUPT_FAIL_CLOSED", rejected)

            val parsed = decode(rowsText)
            check("PARSE_OK", parsed.ok && parsed.code == "OK" && parsed.staleDropped == 0)

            val copyText = String(files.getValue("report-copy.txt"), Charsets.UTF_8)
            val offense = privacyOffense(copyText + "\n" + emitted.joinToString("\n"))
            check("PRIVACY_SCAN", offense == null)

            val encoded = serialize(parsed.entries)
            val encodedKeys = encoded.split('\n').drop(1).dropLastWhile { it.isEmpty() }.map { it.split('\t')[0] }
            check("ROUND_TRIP", decode(encoded).entries == parsed.entries &&
                encodedKeys == encodedKeys.sorted() && encodedKeys.firstOrNull() == parsed.entries.keys.minOrNull())
            check(
                "RANK_STABLE",
                rank(parsed.entries, BASE_UPDATED_MS + (ENTRIES * 1_000L) + 1L) ==
                    (ENTRIES - TOP_LIMIT until ENTRIES).map { "ch" + it.toString().padStart(6, '0') }
            )
            // Pinned fixture expectation: a mutated MAX_ENTRIES must fail here, not redefine the target.
            check("CAP_BINDING", parsed.entries.size == 64)

            val staleDoc = listOf(HEADER,
                line("ch000001", 1000L, 100000L, 2000L),
                line("ch000001", 3000L, 100000L, 1000L),
                line("ch000001", 4000L, 100000L, 2000L)).joinToString("\n", postfix = "\n")
            val stale = decode(staleDoc)
            check("STALE_REJECT", stale.ok && stale.staleDropped == 2 &&
                stale.entries.getValue("ch000001").updatedMs == 2000L &&
                stale.entries.getValue("ch000001").positionMs == 1000L)
        } catch (t: Throwable) {
            if (first.isEmpty()) {
                first = "EXCEPTION"
                out("FIRST\tEXCEPTION")
            }
            out("EXCEPTION\t" + t.javaClass.simpleName)
        }
        check("HEAP_BOUND", Runtime.getRuntime().maxMemory() <= 100_663_296L)
        val threadsAfter = Thread.activeCount()
        check("THREAD_STABLE", threadsAfter == threadsBefore)
        out("MEM\theapMax=" + Runtime.getRuntime().maxMemory())
        out("THREADS\tbefore=$threadsBefore\tafter=$threadsAfter")
        val passes = emitted.count { it.startsWith("CHECK\t") && it.endsWith("\tPASS") }
        val failures = emitted.count { it.startsWith("CHECK\t") && it.endsWith("\tFAIL") }
        out("SUMMARY\tchecks=$passes\tfail=$failures")
        val verdict = first.isEmpty() && failures == 0
        out("VERDICT\t" + if (verdict) "PASS" else "FAIL")
        return if (verdict) 0 else 1
    }
}

package com.devtunasoft.tuneros.qa57

import java.io.File
import java.security.MessageDigest

/**
 * QA-57 framework-free contract: bounded recording-library row-label composition.
 *
 * The slice is presentation-only and complementary to QA-25, which owns catalog paging,
 * ordering and protection data. A finite scratch catalog is rendered into recycled list
 * labels. A completed recording with a location is playable; every other row is marked
 * not playable. A protected row carries one marker, an absent start carries the unknown
 * time fallback, and byte counts are rendered as whole-plus-tenth MiB. The location and
 * failure columns are input evidence only: neither value may reach any emitted label.
 *
 * The same source is compiled into the host gate and the instrumented mirror. Time is a
 * synthetic deterministic token instead of a locale-dependent device formatter. All
 * values are invented; no address, credential, personal code or media payload appears.
 */
object Qa57Contract {
    val TAB: Char = 9.toChar()
    val NL: Char = 10.toChar()
    const val SEED: Long = 20261125L
    const val MAX_ROWS = 256
    const val ROW_COUNT = 256
    const val CORRUPT_COUNT = 10
    const val CASE_COUNT = 8
    const val MAX_BYTES = 1_000_000_000L
    const val MAX_TITLE_CHARS = 20
    const val MAX_EPOCH_DIGITS = 10
    const val MID = "  \u00B7  "

    val ID_RE = Regex("qa57r-[0-9]{3}")
    val TITLE_RE = Regex("title-[0-9]{3}")
    val LOC_RE = Regex("loc-[0-9]{5}")
    val FAIL_RE = Regex("fail-[0-3]")
    val STATES = setOf("COMPLETED", "PARTIAL", "RECORDING", "FAILED")
    val ROW_HEADER = "id\ttitle\tstate\tstarted_epoch\tbytes\tlocation\tfailure\tprotected"
    val CORRUPT_HEADER = "id\tfamily\thex"
    val CASE_HEADER = "case\trow\tlabel_hex"
    val DIM_HEADER = "key\tvalue"
    val REPORT_HEADER = "# qa57 diagnostics carry check ids, counts and digests only"

    val DATA_FILES = arrayOf("rows.tsv", "corrupt.txt", "cases.tsv", "dimensions.tsv", "report-copy.txt")
    val ALL_FILES = arrayOf(*DATA_FILES, "sha256.txt")
    val COUNT_KEYS = arrayOf("rows", "corrupt", "cases", "playable", "not_playable", "protected", "unknown_time")
    val CASE_INDEXES = intArrayOf(0, 1, 2, 3, 4, 17, 36, 255)
    val SIZE_PROBES = longArrayOf(0L, 1L, 524288L, 999999L, 1048576L, 1572864L)

    data class Row(
        val id: String,
        val title: String,
        val state: String,
        val started: String,
        val bytes: Long,
        val location: String,
        val failure: String,
        val protected: Int,
    )

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val chars = "0123456789abcdef".toCharArray()
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(chars[v ushr 4]).append(chars[v and 0x0F])
        }
        return out.toString()
    }

    /** Run-time derived marker: never written as a literal in source or fixtures. */
    fun derivedToken(): String {
        val mix = (SEED * 6364136223846793005L + 1442695040888963407L) and Long.MAX_VALUE
        return "qa57mk" + java.lang.Long.toHexString(mix).padStart(16, '0')
    }

    private fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef".toCharArray()
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(chars[v ushr 4]).append(chars[v and 0x0F])
        }
        return out.toString()
    }

    private fun unhex(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        val chars = "0123456789abcdef"
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = chars.indexOf(value[i * 2].lowercaseChar())
            val lo = chars.indexOf(value[i * 2 + 1].lowercaseChar())
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun strictFields(line: String, expected: Int): List<String>? {
        val fields = line.split(TAB, limit = expected + 1)
        return if (fields.size == expected) fields else null
    }

    private fun printable(value: String, min: Int, max: Int): Boolean {
        if (value.length < min || value.length > max) return false
        for (ch in value) if (ch.code < 0x21 || ch.code > 0x7E) return false
        return true
    }

    /** Strict row admission; hidden controls, unknown states and negative sizes are refused. */
    fun parseRow(line: String): Row? {
        val f = strictFields(line, 8) ?: return null
        val id = f[0]
        val title = f[1]
        val state = f[2]
        val started = f[3]
        val bytes = f[4]
        val location = f[5]
        val failure = f[6]
        val protected = f[7]
        if (!ID_RE.matchEntire(id).isValid()) return null
        if (!TITLE_RE.matchEntire(title).isValid() || title.length > MAX_TITLE_CHARS) return null
        if (state !in STATES) return null
        if (!printable(started, 1, MAX_EPOCH_DIGITS + 1)) return null
        if (started != "-" && started.any { it !in '0'..'9' }) return null
        if (!printable(bytes, 1, 10) || bytes.any { it !in '0'..'9' }) return null
        val size = bytes.toLongOrNull() ?: return null
        if (size < 0 || size > MAX_BYTES) return null
        if (location != "-" && !LOC_RE.matchEntire(location).isValid()) return null
        if (failure != "-" && !FAIL_RE.matchEntire(failure).isValid()) return null
        if (protected != "0" && protected != "1") return null
        return Row(id, title, state, started, size, location, failure, protected.toInt())
    }

    private fun MatchResult?.isValid(): Boolean = this != null

    fun timeLabel(started: String): String =
        if (started == "-") "Unknown time" else "t-" + started.takeLast(6).padStart(6, '0')

    fun sizeLabel(bytes: Long): String {
        val tenths = (bytes * 10L + 524288L) / 1048576L
        return (tenths / 10L).toString() + "." + (tenths % 10L).toString() + " MiB"
    }

    fun isPlayable(row: Row): Boolean = row.state == "COMPLETED" && row.location != "-"

    /** One recycled-list label. The order of markers is part of the contract. */
    fun label(row: Row): String = buildString {
        append(row.title).append(MID).append(row.state)
        if (row.protected == 1) append(MID).append("PROTECTED")
        append('\n')
        append(timeLabel(row.started)).append(MID).append(sizeLabel(row.bytes))
        if (!isPlayable(row)) append(MID).append("Not playable")
    }

    private fun stateOf(i: Int): String = STATES.elementAt((i / 9) % STATES.size)

    private fun bytesOf(i: Int): Long = when (i) {
        0 -> 0L
        1 -> 524288L
        2 -> 1048576L
        3 -> 1572864L
        4 -> 1L
        5 -> 999999L
        else -> 4096L * (i + 1L)
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>(ROW_COUNT)
        for (i in 0 until ROW_COUNT) {
            val started = if (i % 13 == 0) "-" else (1700000000L + i * 37L).toString()
            val location = if (stateOf(i) == "RECORDING" || i % 9 == 0) "-" else "loc-" + ((i * 17) % 97).toString().padStart(5, '0')
            rows.add(
                Row(
                    id = "qa57r-" + i.toString().padStart(3, '0'),
                    title = "title-" + ((i * 7) % 97).toString().padStart(3, '0'),
                    state = stateOf(i),
                    started = started,
                    bytes = bytesOf(i),
                    location = location,
                    failure = if (stateOf(i) == "FAILED") "fail-" + (i % 4) else "-",
                    protected = if (i % 7 == 0) 1 else 0,
                )
            )
        }
        return rows
    }

    private fun corruptVariants(): List<Pair<String, String>> {
        val valid = "qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-\t0"
        val variants = arrayOf(
            "short-fields" to "qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-",
            "extra-field" to valid + "\t0",
            "bad-id" to "qa57x-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-\t0",
            "bad-state" to "qa57r-007\ttitle-049\tDONE\t1700000259\t32768\tloc-00019\tfail-\t0",
            "negative-bytes" to "qa57r-007\ttitle-049\tPARTIAL\t1700000259\t-1\tloc-00019\tfail-\t0",
            "bad-protected" to "qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-\t2",
            "control-char" to "qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-\t0\u0001",
            "long-title" to "qa57r-007\ttitle-049-extra-long-value\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-\t0",
            "bad-location" to "qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-abcde\tfail-\t0",
            "bad-epoch" to "qa57r-007\ttitle-049\tPARTIAL\t17000002x9\t32768\tloc-00019\tfail-\t0",
        )
        return variants.map { (family, payload) -> family to hex(payload.toByteArray(Charsets.UTF_8)) }
    }

    private fun textLines(lines: List<String>): ByteArray =
        (lines.joinToString(NL.toString()) + NL).toByteArray(Charsets.UTF_8)

    private fun table(text: String?): List<String>? {
        if (text == null) return null
        val lines = text.split(NL).dropLastWhile { it.isEmpty() }
        return if (lines.isEmpty()) null else lines
    }

    /** Deterministic scratch fixture. Every byte is a function of the declarations above. */
    fun generate(): Map<String, ByteArray> {
        val rows = buildRows()
        val out = LinkedHashMap<String, ByteArray>()

        val rowLines = ArrayList<String>(ROW_COUNT + 1)
        rowLines.add(ROW_HEADER)
        for (row in rows) rowLines.add(
            listOf(row.id, row.title, row.state, row.started, row.bytes.toString(), row.location,
                row.failure, row.protected.toString()).joinToString(TAB.toString())
        )
        out["rows.tsv"] = textLines(rowLines)

        val corruptLines = ArrayList<String>(CORRUPT_COUNT + 1)
        corruptLines.add(CORRUPT_HEADER)
        var c = 0
        for ((family, payload) in corruptVariants()) {
            c++
            corruptLines.add("c" + c.toString().padStart(2, '0') + TAB + family + TAB + payload)
        }
        out["corrupt.txt"] = textLines(corruptLines)

        val caseLines = ArrayList<String>(CASE_COUNT + 1)
        caseLines.add(CASE_HEADER)
        for ((n, index) in CASE_INDEXES.withIndex()) {
            val row = rows[index]
            caseLines.add("case-" + (n + 1).toString().padStart(2, '0') + TAB + row.id + TAB +
                hex(label(row).toByteArray(Charsets.UTF_8)))
        }
        out["cases.tsv"] = textLines(caseLines)

        val allLabels = rows.joinToString(NL.toString()) { label(it) }
        val values = LinkedHashMap<String, String>()
        values["rows"] = rows.size.toString()
        values["corrupt"] = CORRUPT_COUNT.toString()
        values["cases"] = CASE_COUNT.toString()
        values["playable"] = rows.count { isPlayable(it) }.toString()
        values["not_playable"] = rows.count { !isPlayable(it) }.toString()
        values["protected"] = rows.count { it.protected == 1 }.toString()
        values["unknown_time"] = rows.count { it.started == "-" }.toString()
        values["digest"] = sha256(allLabels.toByteArray(Charsets.UTF_8))
        val dimLines = ArrayList<String>(values.size + 1)
        dimLines.add(DIM_HEADER)
        for ((key, value) in values) dimLines.add(key + TAB + value)
        out["dimensions.tsv"] = textLines(dimLines)

        out["report-copy.txt"] = textLines(listOf(
            REPORT_HEADER,
            "CHECK\tBYTE_IDENTITY\tPASS",
            "CHECK\tPRIVACY_SCAN\tPASS",
            "SUMMARY\tchecks=2\tfail=0",
        ))

        val manifest = ArrayList<String>(DATA_FILES.size)
        for (name in DATA_FILES) manifest.add(sha256(out.getValue(name)) + TAB + name)
        out["sha256.txt"] = textLines(manifest)
        return out
    }

    private fun dimensions(text: String?): Map<String, String>? {
        val lines = table(text) ?: return null
        if (lines[0] != DIM_HEADER) return null
        val out = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val f = strictFields(lines[i], 2) ?: return null
            if (out.put(f[0], f[1]) != null) return null
        }
        return out
    }

    private fun rowTable(text: String?): List<String>? {
        val lines = table(text) ?: return null
        if (lines[0] != ROW_HEADER) return null
        return lines.drop(1)
    }

    private fun corruptTable(text: String?): List<Pair<String, ByteArray>>? {
        val lines = table(text) ?: return null
        if (lines[0] != CORRUPT_HEADER) return null
        val out = ArrayList<Pair<String, ByteArray>>()
        for (i in 1 until lines.size) {
            val f = strictFields(lines[i], 3) ?: return null
            if (!Regex("c[0-9]{2}").matchEntire(f[0]).isValid()) return null
            val bytes = unhex(f[2]) ?: return null
            out.add(f[1] to bytes)
        }
        return out
    }

    private fun caseTable(text: String?): List<Triple<String, String, String>>? {
        val lines = table(text) ?: return null
        if (lines[0] != CASE_HEADER) return null
        val out = ArrayList<Triple<String, String, String>>()
        for (i in 1 until lines.size) {
            val f = strictFields(lines[i], 3) ?: return null
            if (unhex(f[2]) == null) return null
            out.add(Triple(f[0], f[1], f[2]))
        }
        return out
    }

    private val SENSITIVE = arrayOf(
        Regex("(?i)https?://"), Regex("(?i)rtsp://"), Regex("(?i)content://"),
        Regex("(?i)file://"), Regex("(?i)www\\."), Regex("(?i)bearer[ =]"),
        Regex("(?i)password"), Regex("(?i)passwd"), Regex("(?i)api[-_]?key"),
        Regex("(?i)access[-_]?token"), Regex("(?i)secret"), Regex("(?i)credential"),
        Regex("(?i)pin[=:]"), Regex("(?i)token[=:]"), Regex("(?i)\\.m3u8?"),
        Regex("(?i)<xmltv"), Regex("(?i)\\.ts\\b"),
    )

    private fun leakIn(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains(derivedToken())) return true
        for (pattern in SENSITIVE) if (pattern.containsMatchIn(text)) return true
        return false
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /**
     * Runs the contract over committed fixture bytes. Mode selects a structural slice;
     * a focus code makes one named check run first so no mutation hides behind another.
     */
    fun verifyModel(mode: String, files: Map<String, ByteArray>, sink: (String) -> Unit): Int {
        val emitted = ArrayList<String>()
        fun emit(line: String) { emitted.add(line); sink(line) }
        try {
            val threadBefore = Thread.activeCount()
            val generated = generate()
            val rawRows = files["rows.tsv"]?.let { String(it, Charsets.UTF_8) }
            val rawCorrupt = files["corrupt.txt"]?.let { String(it, Charsets.UTF_8) }
            val rawCases = files["cases.tsv"]?.let { String(it, Charsets.UTF_8) }
            val rawDims = files["dimensions.tsv"]?.let { String(it, Charsets.UTF_8) }
            val rawLineRows = rowTable(rawRows)
            val parsed = rawLineRows?.map { parseRow(it) }
            val rows = if (parsed != null && parsed.all { it != null }) parsed.map { it!! } else null
            val corrupt = corruptTable(rawCorrupt)
            val cases = caseTable(rawCases)
            val dims = dimensions(rawDims)
            val labels = rows?.map { label(it) }
            val digest = labels?.let { sha256(it.joinToString(NL.toString()).toByteArray(Charsets.UTF_8)) }

            val checks = ArrayList<Pair<String, () -> Boolean>>()
            fun check(code: String, body: () -> Boolean) { checks.add(code to body) }

            check("BYTE_IDENTITY") {
                files.size == ALL_FILES.size && ALL_FILES.all { name ->
                    val committed = files[name]
                    val expected = generated[name]
                    committed != null && expected != null && committed.contentEquals(expected)
                }
            }
            check("MANIFEST_CONSISTENT") {
                val lines = table(files["sha256.txt"]?.let { String(it, Charsets.UTF_8) }) ?: return@check false
                if (lines.size != DATA_FILES.size) return@check false
                var ok = true
                for (i in DATA_FILES.indices) {
                    val f = strictFields(lines[i], 2) ?: return@check false
                    val data = files[f[1]] ?: return@check false
                    if (f[0] != sha256(data)) ok = false
                }
                ok
            }
            check("PINNED_DIGEST") { dims?.get("digest") != null && dims["digest"] == digest }
            check("HEADER_STRICT") {
                table(rawRows)?.firstOrNull() == ROW_HEADER &&
                    table(rawCorrupt)?.firstOrNull() == CORRUPT_HEADER &&
                    table(rawCases)?.firstOrNull() == CASE_HEADER &&
                    table(rawDims)?.firstOrNull() == DIM_HEADER &&
                    files["report-copy.txt"]?.let { table(String(it, Charsets.UTF_8))?.firstOrNull() } == REPORT_HEADER
            }
            check("ROW_BOUNDS") {
                rawLineRows != null && rawLineRows.all { strictFields(it, 8) != null } &&
                    corrupt != null && corrupt.size == CORRUPT_COUNT && cases != null &&
                    cases.size == CASE_COUNT && dims != null && dims.size == COUNT_KEYS.size + 1
            }
            check("CAP_BINDING") { rawLineRows != null && rawLineRows.size == ROW_COUNT && ROW_COUNT == MAX_ROWS }
            check("CORRUPT_FAIL_CLOSED") {
                corrupt != null && corrupt.isNotEmpty() && corrupt.all { (_, bytes) ->
                    parseRow(String(bytes, Charsets.UTF_8)) == null
                }
            }
            check("UNKNOWN_TIME") {
                rows != null && labels != null && dims != null && run {
                    val case = rows.first { it.started == "-" }
                    val text = labels[rows.indexOf(case)]
                    text.contains("Unknown time") && !text.contains("t-") &&
                        dims["unknown_time"] == rows.count { it.started == "-" }.toString()
                }
            }
            check("PROTECTED_MARKER") {
                rows != null && labels != null && dims != null && rows.indices.all { i ->
                    val marker = MID + "PROTECTED"
                    val has = labels[i].contains(marker) &&
                        labels[i].countOccurrences(marker) == 1
                    has == (rows[i].protected == 1)
                } && dims["protected"] == rows.count { it.protected == 1 }.toString()
            }
            check("PLAYABLE_GATE") {
                rows != null && labels != null && dims != null && rows.indices.all { i ->
                    labels[i].contains(MID + "Not playable") == !isPlayable(rows[i])
                } && dims["playable"] == rows.count { isPlayable(it) }.toString() &&
                    dims["not_playable"] == rows.count { !isPlayable(it) }.toString()
            }
            check("SIZE_ROUNDING") {
                rows != null && labels != null && run {
                    val pinned = mapOf(0L to "0.0 MiB", 1L to "0.0 MiB", 524288L to "0.5 MiB",
                        999999L to "1.0 MiB", 1048576L to "1.0 MiB", 1572864L to "1.5 MiB")
                    SIZE_PROBES.all { bytes -> sizeLabel(bytes) == pinned[bytes] } &&
                        SIZE_PROBES.all { bytes ->
                            val i = rows.indexOfFirst { it.bytes == bytes }
                            i >= 0 && labels[i].contains(sizeLabel(bytes))
                        }
                }
            }
            check("FIELD_OMISSION") {
                rows != null && labels != null && rows.indices.all { i ->
                    val row = rows[i]
                    val evidence = listOf(row.location, row.failure).filter { it != "-" }
                    evidence.all { value -> !labels[i].contains(value) }
                }
            }
            check("CASE_GOLDEN") {
                rows != null && labels != null && cases != null && run {
                    val byId = HashMap<String, String>()
                    for (i in rows.indices) byId[rows[i].id] = labels[i]
                    cases.size == CASE_INDEXES.size && cases.all { (_, id, expectedHex) ->
                        val expected = unhex(expectedHex)?.let { String(it, Charsets.UTF_8) }
                        expected != null && byId[id] == expected
                    }
                }
            }
            check("COUNTS_PINNED") {
                rows != null && dims != null && cases != null && corrupt != null &&
                    dims["rows"] == rows.size.toString() && dims["corrupt"] == corrupt.size.toString() &&
                    dims["cases"] == cases.size.toString()
            }
            check("FAIL_CLOSED_PROBE") {
                parseRow("qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-") == null &&
                    parseRow("qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-\t0\tx") == null &&
                    parseRow("qa57r-007\ttitle-049\tDONE\t1700000259\t32768\tloc-00019\tfail-\t0") == null &&
                    parseRow("qa57r-007\ttitle-049\tPARTIAL\t1700000259\t-1\tloc-00019\tfail-\t0") == null &&
                    parseRow("qa57r-007\ttitle-049\tPARTIAL\t1700000259\t32768\tloc-00019\tfail-\t2") == null
            }
            check("PRIVACY_SCAN") {
                val inputLeak = files.values.any { leakIn(it) }
                val outputLeak = emitted.any { leakIn(it.toByteArray(Charsets.UTF_8)) }
                !inputLeak && !outputLeak
            }
            check("THREAD_DRIFT") { Thread.activeCount() == threadBefore }

            val focus = if (mode.startsWith("verify-mutant:")) mode.substringAfter(":") else null
            val selected = when {
                mode == "verify-byte" -> listOf("BYTE_IDENTITY")
                mode == "verify-manifest" -> listOf("MANIFEST_CONSISTENT")
                mode == "verify-pinned" -> listOf("PINNED_DIGEST")
                focus != null -> checks.map { it.first }
                    .filter { it != "BYTE_IDENTITY" && it != "MANIFEST_CONSISTENT" && it != "PINNED_DIGEST" }
                    .sortedWith(compareByDescending<String> { it == focus }.thenBy { it })
                else -> checks.map { it.first }
            }
            val byCode = checks.associate { it.first to it.second }
            var failures = 0
            var firstCode = "NONE"
            for (code in selected) {
                val ok = try { byCode.getValue(code)() } catch (t: Throwable) { false }
                emit("CHECK\t$code\t${if (ok) "PASS" else "FAIL"}")
                if (!ok) {
                    failures++
                    if (firstCode == "NONE") firstCode = code
                }
            }
            emit("MEM\theapMax=" + Runtime.getRuntime().maxMemory())
            emit("THREADS\tbefore=$threadBefore\tafter=" + Thread.activeCount())
            emit("FIRST\t$firstCode")
            emit("SUMMARY\tchecks=${selected.size}\tfail=$failures")
            emit("VERDICT\t${if (failures == 0) "PASS" else "FAIL"}")
            return if (failures == 0) 0 else 1
        } catch (t: Throwable) {
            emit("FIRST\tHARNESS_EXCEPTION")
            emit("EXCEPTION\t" + t.javaClass.simpleName)
            emit("SUMMARY\tchecks=0\tfail=1")
            emit("VERDICT\tFAIL")
            return 1
        }
    }
}

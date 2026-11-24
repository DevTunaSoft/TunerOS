package com.devtunasoft.tuneros.qa56

import java.io.File
import java.security.MessageDigest

/**
 * QA-56 framework-free contract: bounded channel display-number mapping preservation.
 *
 * A finite scratch channel table is admitted only when every row is well formed. A row with a
 * blank or out-of-range raw number stays unmapped. A number already claimed by an earlier row
 * stays with that row; a later duplicate never steals or renumbers it. Assignments are keyed by
 * the stable channel id, and every prefix of the table must agree with the full mapping.
 *
 * The same source is compiled into the host gate and into the instrumented mirror. Every value is
 * synthetic: no provider address, credential, access code or recording payload is represented.
 */
object Qa56Contract {
    val TAB: Char = 9.toChar()
    val NL: Char = 10.toChar()
    const val SEED: Long = 20261124L

    const val MAX_CHANNELS = 512
    const val CHANNEL_COUNT = 512
    const val MAX_NUMBER = 9999
    const val MAX_RAW_CHARS = 8
    const val MAX_LABEL_CHARS = 24
    const val CORRUPT_COUNT = 10
    const val DUPLICATE_NUMBER = 112
    const val HEAP_LIMIT = 100663296L

    val ID_RE = Regex("qa56c-[0-9]{4}")
    val CORRUPT_ID_RE = Regex("c[0-9]{2}")
    val CHANNEL_HEADER = "id" + TAB + "raw" + TAB + "label"
    val DIM_HEADER = "key" + TAB + "value"
    val CORRUPT_HEADER = "id" + TAB + "family" + TAB + "hex"
    val REPORT_HEADER = "# qa56 diagnostics carry check ids, counts and digests only"

    val DATA_FILES = arrayOf("channels.tsv", "corrupt.txt", "dimensions.tsv", "report-copy.txt")
    val ALL_FILES = arrayOf(*DATA_FILES, "sha256.txt")
    val COUNT_KEYS = arrayOf(
        "channels", "valid", "duplicate", "blank", "out_of_range", "unmapped", "assigned", "probes"
    )

    data class Row(val id: String, val raw: String, val label: String)

    data class Assignment(
        val entries: List<Pair<String, Int?>>,
        val duplicate: Int,
        val blank: Int,
        val outOfRange: Int,
        val mapped: Int,
        val digest: String,
    ) {
        val unmapped: Int get() = entries.size - mapped
    }

    private enum class Kind { BLANK, VALID, OUT_OF_RANGE, CORRUPT }

    private class Decision(val kind: Kind, val number: Int = -1)

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

    /** Run-time derived marker: never stored as a literal in source or fixtures. */
    fun derivedToken(): String {
        val mix = (SEED * 6364136223846793005L + 1442695040888963407L) and Long.MAX_VALUE
        return "qa56mk" + java.lang.Long.toHexString(mix).padStart(16, '0')
    }

    private fun rawFor(index: Int): String = when {
        index % 17 == 0 -> ""
        index % 29 == 0 -> "   "
        index % 23 == 0 -> if (index % 2 == 0) "0" else (MAX_NUMBER + 1 + index % 7).toString()
        index % 11 == 0 -> DUPLICATE_NUMBER.toString()
        else -> ((index * 37) % 251 + 1).toString()
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>(CHANNEL_COUNT)
        for (i in 0 until CHANNEL_COUNT) {
            val id = "qa56c-" + i.toString().padStart(4, '0')
            val label = "label-" + (i % 97).toString().padStart(3, '0')
            rows.add(Row(id, rawFor(i), label))
        }
        return rows
    }

    private fun textLines(lines: List<String>): ByteArray =
        (lines.joinToString(NL.toString()) + NL).toByteArray(Charsets.UTF_8)

    private fun strictFields(line: String, expected: Int): List<String>? {
        val fields = line.split(TAB, limit = expected + 1)
        return if (fields.size == expected) fields else null
    }

    /** Strict row admission: no hidden controls, no unknown id shape, no non-numeric raw. */
    fun parseRow(line: String): Row? {
        val f = strictFields(line, 3) ?: return null
        val id = f[0]
        val raw = f[1]
        val label = f[2]
        if (!ID_RE.matchEntire(id).isValid()) return null
        if (raw.length > MAX_RAW_CHARS) return null
        for (ch in raw) if (ch.code < 0x20 || ch.code > 0x7E) return null
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty()) for (ch in trimmed) if (ch !in '0'..'9') return null
        if (label.isEmpty() || label.length > MAX_LABEL_CHARS) return null
        for (ch in label) if (ch.code < 0x21 || ch.code > 0x7E) return null
        return Row(id, raw, label)
    }

    private fun MatchResult?.isValid(): Boolean = this != null

    private fun classify(raw: String): Decision {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Decision(Kind.BLANK)
        val number = trimmed.toLongOrNull() ?: return Decision(Kind.CORRUPT)
        if (number < 1 || number > MAX_NUMBER) return Decision(Kind.OUT_OF_RANGE)
        return Decision(Kind.VALID, number.toInt())
    }

    /** The bounded mapping itself. First claimant keeps a number; later duplicates stay unmapped. */
    fun assign(rows: List<Row>): Assignment {
        val taken = HashMap<Int, String>()
        val entries = ArrayList<Pair<String, Int?>>(rows.size)
        var duplicate = 0
        var blank = 0
        var outOfRange = 0
        var mapped = 0
        for (row in rows) {
            val decision = classify(row.raw)
            when (decision.kind) {
                Kind.BLANK -> { blank++; entries.add(row.id to null) }
                Kind.OUT_OF_RANGE -> { outOfRange++; entries.add(row.id to null) }
                Kind.CORRUPT -> { outOfRange++; entries.add(row.id to null) }
                Kind.VALID -> {
                    val number = decision.number
                    if (taken.putIfAbsent(number, row.id) == null) {
                        entries.add(row.id to number)
                        mapped++
                    } else {
                        duplicate++
                        entries.add(row.id to null)
                    }
                }
            }
        }
        val canonical = entries.joinToString(";") { (id, number) -> id + ":" + (number?.toString() ?: "-") }
        return Assignment(entries, duplicate, blank, outOfRange, mapped, sha256(canonical.toByteArray(Charsets.UTF_8)))
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

    private fun corruptVariants(): List<Pair<String, String>> {
        val valid = "qa56c-0007\t7\tlabel-007"
        val variants = arrayOf(
            "short-fields" to "qa56c-0007\t7",
            "extra-field" to valid + "\t7",
            "bad-id" to "qa56x-0007\t7\tlabel-007",
            "bad-raw" to "qa56c-0007\t7x\tlabel-007",
            "control-char" to "qa56c-0007\t7\tlabel-007\u0001",
            "blank-label" to "qa56c-0007\t7\t",
            "long-label" to "qa56c-0007\t7\t" + "x".repeat(MAX_LABEL_CHARS + 1),
            "raw-overflow" to "qa56c-0007\t123456789\tlabel-007",
            "leading-space" to " qa56c-0007\t7\tlabel-007",
            "bad-index" to "qa56c-00Ax\t7\tlabel-007",
        )
        return variants.map { (family, line) -> family to hex(line.toByteArray(Charsets.UTF_8)) }
    }

    /** Deterministic scratch fixture; every byte is a function of the constants above. */
    fun generate(): Map<String, ByteArray> {
        val rows = buildRows()
        val result = assign(rows)
        val out = LinkedHashMap<String, ByteArray>()
        val channelLines = ArrayList<String>(CHANNEL_COUNT + 1)
        channelLines.add(CHANNEL_HEADER)
        for (row in rows) channelLines.add(row.id + TAB + row.raw + TAB + row.label)
        out["channels.tsv"] = textLines(channelLines)

        val corruptLines = ArrayList<String>(CORRUPT_COUNT + 1)
        corruptLines.add(CORRUPT_HEADER)
        var index = 0
        for ((family, payload) in corruptVariants()) {
            index++
            corruptLines.add("c" + index.toString().padStart(2, '0') + TAB + family + TAB + payload)
        }
        out["corrupt.txt"] = textLines(corruptLines)

        val values = LinkedHashMap<String, String>()
        values["channels"] = rows.size.toString()
        values["valid"] = rows.count { classify(it.raw).kind == Kind.VALID }.toString()
        values["duplicate"] = result.duplicate.toString()
        values["blank"] = result.blank.toString()
        values["out_of_range"] = result.outOfRange.toString()
        values["unmapped"] = result.unmapped.toString()
        values["assigned"] = result.mapped.toString()
        values["probes"] = "5"
        values["digest"] = result.digest
        val dimLines = ArrayList<String>(values.size + 1)
        dimLines.add(DIM_HEADER)
        for ((key, value) in values) dimLines.add(key + TAB + value)
        out["dimensions.tsv"] = textLines(dimLines)

        val reportLines = listOf(
            REPORT_HEADER,
            "CHECK\tBYTE_IDENTITY\tPASS",
            "CHECK\tPINNED_DIGEST\tPASS",
            "SUMMARY\tchecks=2\tfail=0",
        )
        out["report-copy.txt"] = textLines(reportLines)

        val manifest = ArrayList<String>(DATA_FILES.size)
        for (name in DATA_FILES) manifest.add(sha256(out.getValue(name)) + TAB + name)
        out["sha256.txt"] = textLines(manifest)
        return out
    }

    private fun table(text: String?): List<String>? {
        if (text == null) return null
        val lines = text.split(NL).dropLastWhile { it.isEmpty() }
        return if (lines.isEmpty()) null else lines
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

    private fun corruptTable(text: String?): List<Pair<String, ByteArray>>? {
        val lines = table(text) ?: return null
        if (lines[0] != CORRUPT_HEADER) return null
        val out = ArrayList<Pair<String, ByteArray>>()
        for (i in 1 until lines.size) {
            val f = strictFields(lines[i], 3) ?: return null
            if (!CORRUPT_ID_RE.matchEntire(f[0]).isValid()) return null
            val bytes = unhex(f[2]) ?: return null
            out.add(f[1] to bytes)
        }
        return out
    }

    private fun channelTable(text: String?): List<Row>? {
        val lines = table(text) ?: return null
        if (lines[0] != CHANNEL_HEADER) return null
        val out = ArrayList<Row>(lines.size - 1)
        for (i in 1 until lines.size) out.add(parseRow(lines[i]) ?: return null)
        return out
    }

    private val SENSITIVE = arrayOf(
        Regex("(?i)https?://"), Regex("(?i)rtsp://"), Regex("(?i)www\\."),
        Regex("(?i)bearer[ =]"), Regex("(?i)password"), Regex("(?i)passwd"),
        Regex("(?i)api[-_]?key"), Regex("(?i)secret"), Regex("(?i)credential"),
        Regex("(?i)pin[=:]"), Regex("(?i)token[=:]"), Regex("(?i)\\.m3u8?"),
        Regex("(?i)<xmltv"), Regex("(?i)\\.ts\\b"),
    )

    private fun leakIn(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains(derivedToken())) return true
        for (pattern in SENSITIVE) if (pattern.containsMatchIn(text)) return true
        return false
    }

    /**
     * Runs the contract over committed fixture bytes. Mode selects a structural slice; the optional
     * focus code makes one named check run first so a mutation cannot hide behind another failure.
     */
    fun verifyModel(mode: String, files: Map<String, ByteArray>, sink: (String) -> Unit): Int {
        val emitted = ArrayList<String>()
        fun emit(line: String) {
            emitted.add(line)
            sink(line)
        }
        try {
            val threadBefore = Thread.activeCount()
            val generated = generate()
            val rawChannels = files["channels.tsv"]?.let { String(it, Charsets.UTF_8) }
            val rawCorrupt = files["corrupt.txt"]?.let { String(it, Charsets.UTF_8) }
            val rawDims = files["dimensions.tsv"]?.let { String(it, Charsets.UTF_8) }
            val rows = channelTable(rawChannels)
            val corrupt = corruptTable(rawCorrupt)
            val dims = dimensions(rawDims)
            val assignment = rows?.let { assign(it) }

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
            check("PINNED_DIGEST") { dims?.get("digest") != null && dims["digest"] == assignment?.digest }
            check("HEADER_STRICT") {
                table(rawChannels)?.firstOrNull() == CHANNEL_HEADER &&
                    table(rawCorrupt)?.firstOrNull() == CORRUPT_HEADER &&
                    table(rawDims)?.firstOrNull() == DIM_HEADER &&
                    files["report-copy.txt"]?.let { table(String(it, Charsets.UTF_8))?.firstOrNull() } == REPORT_HEADER
            }
            check("ROW_BOUNDS") {
                rows != null && rows.size <= MAX_CHANNELS && corrupt != null &&
                    corrupt.size == CORRUPT_COUNT && dims != null && dims.size == COUNT_KEYS.size + 1
            }
            check("CAP_BINDING") { rows != null && rows.size == CHANNEL_COUNT && CHANNEL_COUNT == MAX_CHANNELS }
            check("CORRUPT_FAIL_CLOSED") {
                corrupt != null && corrupt.isNotEmpty() && corrupt.all { (_, bytes) ->
                    parseRow(String(bytes, Charsets.UTF_8)) == null
                }
            }
            check("BLANK_REJECT") {
                rows != null && assignment != null && dims != null &&
                    rows.zip(assignment.entries).all { (row, entry) ->
                        classify(row.raw).kind != Kind.BLANK || entry.second == null
                    } && dims["blank"] == assignment.blank.toString()
            }
            check("RANGE_REJECT") {
                rows != null && assignment != null && dims != null &&
                    rows.zip(assignment.entries).all { (row, entry) ->
                        val kind = classify(row.raw).kind
                        (kind != Kind.OUT_OF_RANGE && kind != Kind.CORRUPT) || entry.second == null
                    } && dims["out_of_range"] == assignment.outOfRange.toString()
            }
            check("DUPLICATE_FIRST_WINS") {
                rows != null && assignment != null && dims != null && run {
                    val truth = HashMap<Int, String>()
                    for (row in rows) {
                        val decision = classify(row.raw)
                        if (decision.kind == Kind.VALID && !truth.containsKey(decision.number)) {
                            truth[decision.number] = row.id
                        }
                    }
                    val actual = HashMap<Int, String>()
                    for (i in rows.indices) {
                        val number = assignment.entries[i].second
                        if (number != null) actual[number] = rows[i].id
                    }
                    dims["duplicate"] == assignment.duplicate.toString() &&
                        truth.all { (number, id) -> actual[number] == id }
                }
            }
            check("ASSIGNMENT_UNIQUE") {
                assignment != null && dims != null && run {
                    val numbers = assignment.entries.mapNotNull { it.second }
                    numbers.size == assignment.mapped && numbers.toSet().size == numbers.size &&
                        numbers.all { it in 1..MAX_NUMBER } && dims["assigned"] == numbers.size.toString()
                }
            }
            check("PREFIX_STABLE") {
                rows != null && assignment != null && dims != null && dims["probes"] == "5" &&
                    listOf(64, 128, 256, 511, 512).all { n ->
                        assign(rows.take(n)).entries == assignment.entries.take(n)
                    }
            }
            check("COUNTS_PINNED") {
                assignment != null && dims != null && rows != null && run {
                    val expected = mapOf(
                        "channels" to rows.size.toString(),
                        "duplicate" to assignment.duplicate.toString(),
                        "blank" to assignment.blank.toString(),
                        "out_of_range" to assignment.outOfRange.toString(),
                        "unmapped" to assignment.unmapped.toString(),
                        "assigned" to assignment.mapped.toString(),
                    )
                    COUNT_KEYS.filter { it != "valid" && it != "probes" }.all { dims[it] == expected[it] }
                }
            }
            check("FAIL_CLOSED_PROBE") {
                parseRow("qa56c-0007\t7x\tlabel-007") == null &&
                    parseRow("qa56c-0007\t7") == null &&
                    classify("   ").kind == Kind.BLANK &&
                    classify("0").kind == Kind.OUT_OF_RANGE &&
                    classify("7x").kind == Kind.CORRUPT
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
                focus != null -> checks.map { it.first }.filter { it != "BYTE_IDENTITY" && it != "MANIFEST_CONSISTENT" && it != "PINNED_DIGEST" }
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

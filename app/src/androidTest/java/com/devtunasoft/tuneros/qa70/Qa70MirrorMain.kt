package com.devtunasoft.tuneros.qa70

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.system.exitProcess

/** A single result line from the gate. Diagnostics carry ids and counts only. */
data class Check(val id: String, val ok: Boolean)

/**
 * QA-70 Android-free mirror. The same byte generator, strict parsers and reference rules
 * compile into the host gate and into the instrumented APK. No Android import, no network
 * and no provider-shaped literal lives here.
 */
object Qa70MirrorMain {
    const val SEED = 20261124
    const val CASE_COUNT = 88
    const val FAMILY_COUNT = 11
    const val RANGE_CAP = 200000L
    const val KEY_BYTES = 16
    const val MAX_BODY = 64

    private val NL = Char(10).toString()
    private val TAB = Char(9).toString()
    private val HEX = "0123456789abcdef"
    private val FILE_NAMES = listOf(
        ".gitattributes", "README.md", "contract.txt", "dimensions.tsv",
        "cases.tsv", "corrupt.tsv", "sha256.txt",
    )
    private val METHODS = setOf("R", "K", "C")
    private val EXPECTATIONS = setOf(
        "OK_RANGE", "REFUSE_RANGE_BOUNDS", "REFUSE_RANGE_STATUS", "HTTP_STATUS",
        "REFUSE_RANGE_SHORT", "OK_KEY", "REFUSE_KEY_SIZE", "NOT_PUBLISHED",
    )
    private val CORRUPT_CODES = setOf(
        "FIELD_COUNT", "INT_BOUNDS", "HEX_INVALID", "STATUS_BOUNDS",
        "METHOD_UNKNOWN", "EXPECT_UNKNOWN", "CAP_BOUNDS", "DUPLICATE_ID",
    )

    data class Case(
        val id: String, val method: String, val length: Long, val status: Int,
        val body: ByteArray, val expect: String, val detail: String, val offset: Long,
    )

    data class CorruptRow(val id: String, val code: String, val payload: String)

    private fun sha(bytes: ByteArray): String {
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(raw.size * 2)
        for (value in raw) {
            val v = value.toInt() and 0xff
            out.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return out.toString()
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (value in bytes) {
            val v = value.toInt() and 0xff
            out.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return out.toString()
    }

    private fun unhex(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = HEX.indexOf(value[i * 2].lowercaseChar())
            val lo = HEX.indexOf(value[i * 2 + 1].lowercaseChar())
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun derivedMarker(): String {
        var h = 2166136261L
        for (b in SEED.toString().toByteArray(StandardCharsets.US_ASCII)) {
            h = ((h xor (b.toLong() and 0xffL)) * 16777619L) and 0xffffffffL
        }
        return "QA70-" + java.lang.Long.toHexString(h).padStart(8, '0')
    }

    private fun privacyPatterns(): List<String> {
        val sep = "" + ':' + '/' + '/'
        val keys = listOf(
            "pass" + "word", "pin", "sec" + "ret", "to" + "ken", "api" + "_" + "key",
            "cred" + "ential", "authorization", "secret", "drm" + "-" + "key",
            "recording" + "-" + "payload",
        )
        val out = mutableListOf(sep, derivedMarker())
        for (key in keys) {
            out.add(key + '=')
            out.add(key + ':')
        }
        return out
    }

    private fun bodyBytes(index: Int, count: Int): ByteArray {
        val out = ByteArray(count)
        for (j in out.indices) {
            out[j] = ((SEED + index * 31 + j * 7) and 0xff).toByte()
        }
        return out
    }

    private fun makeCases(): List<Case> {
        val out = ArrayList<Case>(CASE_COUNT)
        val statuses3 = listOf(200, 201, 202, 203, 204, 205, 207, 299)
        val statuses4 = listOf(400, 401, 403, 404, 500, 502, 503, 599)
        for (i in 0 until CASE_COUNT) {
            val family = i / 8
            val variant = i % 8
            val id = "C" + (i + 1).toString().padStart(3, '0')
            val offset = 4096L + i * 17L
            val body: ByteArray
            val length: Long
            val status: Int
            val method: String
            val expect: String
            val detail: String
            when (family) {
                0 -> {
                    method = "R"; length = (1 + variant).toLong(); status = 206
                    body = bodyBytes(i, length.toInt()); expect = "OK_RANGE"; detail = hex(body)
                }
                1 -> {
                    method = "R"; length = if (variant % 2 == 0) 0L else -(1L + variant / 2L)
                    status = 206; body = bodyBytes(i, 1)
                    expect = "REFUSE_RANGE_BOUNDS"; detail = "-"
                }
                2 -> {
                    method = "R"; length = RANGE_CAP + 1L + variant.toLong(); status = 206
                    body = bodyBytes(i, 1); expect = "REFUSE_RANGE_BOUNDS"; detail = "-"
                }
                3 -> {
                    method = "R"; length = (1 + variant).toLong(); status = statuses3[variant]
                    body = bodyBytes(i, length.toInt()); expect = "REFUSE_RANGE_STATUS"; detail = "-"
                }
                4 -> {
                    method = "R"; length = (1 + variant).toLong(); status = statuses4[variant]
                    body = bodyBytes(i, length.toInt()); expect = "HTTP_STATUS"; detail = status.toString()
                }
                5 -> {
                    method = "R"; length = (4 + variant).toLong(); status = 206
                    body = bodyBytes(i, 1 + variant / 2)
                    expect = "REFUSE_RANGE_SHORT"; detail = "-"
                }
                6 -> {
                    method = "K"; length = 0L; status = 200
                    body = bodyBytes(i, KEY_BYTES); expect = "OK_KEY"; detail = hex(body)
                }
                7 -> {
                    method = "K"; length = 0L; status = 200
                    body = bodyBytes(i, 15 - variant % 6); expect = "OK_KEY"; detail = hex(body)
                }
                8 -> {
                    method = "K"; length = 0L; status = 200
                    body = ByteArray(0); expect = "OK_KEY"; detail = "-"
                }
                9 -> {
                    method = "K"; length = 0L; status = 200
                    body = bodyBytes(i, KEY_BYTES + 1 + variant)
                    expect = "REFUSE_KEY_SIZE"; detail = "-"
                }
                else -> {
                    method = "C"; length = 0L; status = 404
                    body = ByteArray(0); expect = "NOT_PUBLISHED"; detail = "-"
                }
            }
            out += Case(id, method, length, status, body, expect, detail, offset)
        }
        return out
    }

    private fun caseLine(c: Case): String = listOf(
        c.id, c.method, c.length.toString(), c.status.toString(),
        if (c.body.isEmpty()) "-" else hex(c.body), c.expect, c.detail, c.offset.toString(),
    ).joinToString(TAB)

    private val CASE_HEADER: String = listOf(
        "id", "method", "len", "status", "bodyHex", "expect", "detail", "offset",
    ).joinToString(TAB)

    private val CORRUPT_HEADER: String = listOf("id", "code", "payload").joinToString(TAB)

    private fun casesText(cases: List<Case>): String = buildString {
        append(CASE_HEADER).append(NL)
        for (c in cases) append(caseLine(c)).append(NL)
    }

    private fun corruptText(): String {
        val rows = listOf(
            CorruptRow("X01", "FIELD_COUNT", "X01" + TAB + "R" + TAB + "4"),
            CorruptRow("X02", "INT_BOUNDS", "X02" + TAB + "R" + TAB + "abc" + TAB + "206" + TAB + "01" + TAB + "OK_RANGE" + TAB + "01" + TAB + "4096"),
            CorruptRow("X03", "HEX_INVALID", "X03" + TAB + "R" + TAB + "4" + TAB + "206" + TAB + "zzzz" + TAB + "OK_RANGE" + TAB + "01" + TAB + "4096"),
            CorruptRow("X04", "STATUS_BOUNDS", "X04" + TAB + "R" + TAB + "4" + TAB + "999" + TAB + "01" + TAB + "OK_RANGE" + TAB + "01" + TAB + "4096"),
            CorruptRow("X05", "METHOD_UNKNOWN", "X05" + TAB + "Q" + TAB + "4" + TAB + "206" + TAB + "01" + TAB + "OK_RANGE" + TAB + "01" + TAB + "4096"),
            CorruptRow("X06", "EXPECT_UNKNOWN", "X06" + TAB + "R" + TAB + "4" + TAB + "206" + TAB + "01" + TAB + "WAT" + TAB + "01" + TAB + "4096"),
            CorruptRow("X07", "CAP_BOUNDS", "X07" + TAB + "R" + TAB + "999999" + TAB + "206" + TAB + "01" + TAB + "OK_RANGE" + TAB + "01" + TAB + "4096"),
            CorruptRow("X08", "DUPLICATE_ID", "C001" + TAB + "R" + TAB + "4" + TAB + "206" + TAB + "01" + TAB + "OK_RANGE" + TAB + "01" + TAB + "4096"),
        )
        return buildString {
            append(CORRUPT_HEADER).append(NL)
            for (r in rows) append(r.id).append(TAB).append(r.code).append(TAB).append(r.payload).append(NL)
        }
    }

    private fun contractText(): String = buildString {
        append("QA-70 scratch contract.").append(NL)
        append("Bounded adapter response admission: DASH index byte ranges and HLS AES key bodies.").append(NL)
        append("All identifiers, sizes and bodies are invented scratch values.").append(NL)
        append("No provider address, credential, personal identification number, protected-content").append(NL)
        append("key or recorded media payload is represented here.").append(NL)
    }

    private fun readmeText(): String = buildString {
        append("# QA-70 fixture").append(NL).append(NL)
        append("Synthetic bounded scratch data, regenerated byte-for-byte by Qa70Gate generate.").append(NL)
        append("The verifier never writes into this directory. Every identifier is invented.").append(NL)
        append("No provider address, credential, personal identification number, protected-content").append(NL)
        append("key or recording payload is stored here.").append(NL)
    }

    private fun attrsText(): String = buildString {
        append("* -text").append(NL).append(".gitattributes -text").append(NL)
    }

    private fun dimensionsText(bytes: Map<String, ByteArray>): String = buildString {
        append("seed").append(TAB).append(SEED).append(NL)
        append("case_count").append(TAB).append(CASE_COUNT).append(NL)
        append("family_count").append(TAB).append(FAMILY_COUNT).append(NL)
        append("range_cap").append(TAB).append(RANGE_CAP).append(NL)
        append("key_bytes").append(TAB).append(KEY_BYTES).append(NL)
        append("cases_digest").append(TAB).append(sha(bytes.getValue("cases.tsv"))).append(NL)
        append("corrupt_digest").append(TAB).append(sha(bytes.getValue("corrupt.tsv"))).append(NL)
        append("contract_digest").append(TAB).append(sha(bytes.getValue("contract.txt"))).append(NL)
        append("readme_digest").append(TAB).append(sha(bytes.getValue("README.md"))).append(NL)
        append("attrs_digest").append(TAB).append(sha(bytes.getValue(".gitattributes"))).append(NL)
    }

    private fun manifestText(bytes: Map<String, ByteArray>): String = buildString {
        for (name in listOf(".gitattributes", "README.md", "contract.txt", "dimensions.tsv", "cases.tsv", "corrupt.tsv")) {
            append(sha(bytes.getValue(name))).append(TAB).append(name).append(NL)
        }
    }

    private fun allBytes(): Map<String, ByteArray> {
        val cases = casesText(makeCases()).toByteArray(StandardCharsets.UTF_8)
        val corrupt = corruptText().toByteArray(StandardCharsets.UTF_8)
        val contract = contractText().toByteArray(StandardCharsets.UTF_8)
        val readme = readmeText().toByteArray(StandardCharsets.UTF_8)
        val attrs = attrsText().toByteArray(StandardCharsets.UTF_8)
        val base = linkedMapOf(
            ".gitattributes" to attrs, "README.md" to readme, "contract.txt" to contract,
            "cases.tsv" to cases, "corrupt.tsv" to corrupt,
        )
        val dimensions = dimensionsText(base).toByteArray(StandardCharsets.UTF_8)
        base["dimensions.tsv"] = dimensions
        base["sha256.txt"] = manifestText(base).toByteArray(StandardCharsets.UTF_8)
        return base
    }

    fun regenerate(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) error("fixture-directory")
        for ((name, bytes) in allBytes()) {
            File(dir, name).writeBytes(bytes)
        }
    }

    internal fun parseCases(source: String): Pair<List<Case>, String?> {
        val lines = source.split(NL).dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != CASE_HEADER) return Pair(emptyList(), "HEADER_STRICT")
        val out = ArrayList<Case>(CASE_COUNT)
        val seen = HashSet<String>()
        for (line in lines.drop(1)) {
            val f = line.split(TAB, limit = 8)
            if (f.size != 8) return Pair(emptyList(), "FIELD_COUNT")
            if (f[0].isEmpty() || f[0].length > 12) return Pair(emptyList(), "ID_BOUNDS")
            if (!seen.add(f[0])) return Pair(emptyList(), "DUPLICATE_ID")
            if (f[1] !in METHODS) return Pair(emptyList(), "METHOD_UNKNOWN")
            val length = f[2].toLongOrNull() ?: return Pair(emptyList(), "INT_BOUNDS")
            val status = f[3].toIntOrNull() ?: return Pair(emptyList(), "INT_BOUNDS")
            if (status !in 100..599) return Pair(emptyList(), "STATUS_BOUNDS")
            val body = if (f[4] == "-") ByteArray(0) else unhex(f[4]) ?: return Pair(emptyList(), "HEX_INVALID")
            if (body.size > MAX_BODY) return Pair(emptyList(), "BODY_BOUNDS")
            if (f[5] !in EXPECTATIONS) return Pair(emptyList(), "EXPECT_UNKNOWN")
            if (f[6].length > 130) return Pair(emptyList(), "DETAIL_BOUNDS")
            val offset = f[7].toLongOrNull() ?: return Pair(emptyList(), "INT_BOUNDS")
            if (length > RANGE_CAP + 8L || length < -16L || offset < 0L || offset > 1_000_000L) {
                return Pair(emptyList(), "CAP_BOUNDS")
            }
            out += Case(f[0], f[1], length, status, body, f[5], f[6], offset)
        }
        if (out.size != CASE_COUNT) return Pair(emptyList(), "ROW_BOUNDS")
        return Pair(out, null)
    }

    internal fun parseCorrupt(source: String): Pair<List<CorruptRow>, String?> {
        val lines = source.split(NL).dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != CORRUPT_HEADER) return Pair(emptyList(), "CORRUPT_HEADER")
        val out = ArrayList<CorruptRow>()
        for (line in lines.drop(1)) {
            val f = line.split(TAB, limit = 3)
            if (f.size != 3) return Pair(emptyList(), "CORRUPT_FIELDS")
            if (f[1] !in CORRUPT_CODES) return Pair(emptyList(), "CORRUPT_CODE")
            if (f[2].length > 130) return Pair(emptyList(), "CORRUPT_BOUNDS")
            out += CorruptRow(f[0], f[1], f[2])
        }
        if (out.size != 8) return Pair(emptyList(), "CORRUPT_ROWS")
        return Pair(out, null)
    }

    internal fun parseDimensions(source: String): Pair<Map<String, String>, String?> {
        val out = LinkedHashMap<String, String>()
        for (line in source.split(NL).dropLastWhile { it.isEmpty() }) {
            val f = line.split(TAB, limit = 2)
            if (f.size != 2 || f[0].isEmpty()) return Pair(emptyMap(), "DIMENSION_FIELD")
            if (out.containsKey(f[0])) return Pair(emptyMap(), "DIMENSION_DUP")
            out[f[0]] = f[1]
        }
        if (out.size != 10) return Pair(emptyMap(), "DIMENSION_ROWS")
        return Pair(out, null)
    }

    internal fun parseManifest(source: String): Pair<Map<String, String>, String?> {
        val out = LinkedHashMap<String, String>()
        for (line in source.split(NL).dropLastWhile { it.isEmpty() }) {
            val f = line.split(TAB, limit = 2)
            if (f.size != 2 || f[0].length != 64 || f[0].any { HEX.indexOf(it.lowercaseChar()) < 0 }) {
                return Pair(emptyMap(), "MANIFEST_FIELD")
            }
            if (out.containsKey(f[1])) return Pair(emptyMap(), "MANIFEST_DUP")
            out[f[1]] = f[0]
        }
        if (out.size != 6) return Pair(emptyMap(), "MANIFEST_ROWS")
        return Pair(out, null)
    }

    /** Bounded parse cycle used by the heap and thread drift probe. */
    fun modelCycle(casesBytes: ByteArray): Int {
        val parsed = parseCases(String(casesBytes, StandardCharsets.UTF_8))
        return parsed.first.size + sha(casesBytes).length
    }

    /**
     * Model-side checks. Structural gates can be suppressed one family at a time so a
     * named mutant reaches its own guard instead of a digest mismatch.
     */
    fun checks(
        dir: File,
        structural: Boolean,
        manifest: Boolean,
        pinned: Boolean,
        extraReport: File? = null,
    ): List<Check> {
        val result = ArrayList<Check>()
        fun add(id: String, ok: Boolean) { result += Check(id, ok) }
        val expectedBytes = allBytes()
        val disk = LinkedHashMap<String, ByteArray>()
        for (name in FILE_NAMES) {
            val file = File(dir, name)
            if (!file.isFile) { add("BYTE_IDENTITY", false); return result }
            disk[name] = file.readBytes()
        }
        if (structural) {
            add("BYTE_IDENTITY", FILE_NAMES.all { disk[it]!!.contentEquals(expectedBytes[it]!!) })
        }
        if (manifest) {
            val parsed = parseManifest(String(disk.getValue("sha256.txt"), StandardCharsets.UTF_8))
            val ok = parsed.second == null && parsed.first.size == 6 &&
                parsed.first.all { (name, digest) -> disk[name] != null && sha(disk[name]!!) == digest }
            add("MANIFEST_CONSISTENT", ok)
        }
        if (pinned) {
            val parsed = parseDimensions(String(disk.getValue("dimensions.tsv"), StandardCharsets.UTF_8))
            val dims = parsed.first
            val ok = parsed.second == null &&
                dims["seed"] == SEED.toString() &&
                dims["case_count"] == CASE_COUNT.toString() &&
                dims["family_count"] == FAMILY_COUNT.toString() &&
                dims["range_cap"] == RANGE_CAP.toString() &&
                dims["key_bytes"] == KEY_BYTES.toString() &&
                dims["cases_digest"] == sha(disk.getValue("cases.tsv")) &&
                dims["corrupt_digest"] == sha(disk.getValue("corrupt.tsv")) &&
                dims["contract_digest"] == sha(disk.getValue("contract.txt")) &&
                dims["readme_digest"] == sha(disk.getValue("README.md")) &&
                dims["attrs_digest"] == sha(disk.getValue(".gitattributes"))
            add("PINNED_DIGEST", ok)
        }
        val cases = parseCases(String(disk.getValue("cases.tsv"), StandardCharsets.UTF_8))
        if (cases.second != null) { add(cases.second!!, false); return result }
        add("HEADER_STRICT", true)
        add("ROW_BOUNDS", cases.first.size == CASE_COUNT)
        add("FIELD_BOUNDS", cases.first.all { it.body.size <= MAX_BODY && it.detail.length <= 130 })

        val corrupt = parseCorrupt(String(disk.getValue("corrupt.tsv"), StandardCharsets.UTF_8))
        if (corrupt.second != null) { add(corrupt.second!!, false); return result }
        val firstCase = caseLine(cases.first[0])
        var rejectOk = true
        for (row in corrupt.first) {
            val text = if (row.code == "DUPLICATE_ID") CASE_HEADER + NL + firstCase + NL + row.payload + NL
            else CASE_HEADER + NL + row.payload + NL
            if (parseCases(text).second != row.code) { rejectOk = false; break }
        }
        add("CORRUPT_REJECT", rejectOk)

        val patterns = privacyPatterns()
        fun leaks(text: String): Boolean {
            val lower = text.lowercase()
            val safe = lower.replace("http" + patterns[0] + "schemas." + "android.com/apk/res/" + "android", "")
            return patterns.any { it.lowercase() in safe }
        }
        var privacyOk = !leaks(disk.values.joinToString(NL) {
            String(it, 0, minOf(it.size, 64 * 1024), StandardCharsets.UTF_8)
        })
        if (privacyOk && extraReport != null && extraReport.isFile) {
            privacyOk = !leaks(String(extraReport.readBytes(), StandardCharsets.UTF_8))
        }
        add("PRIVACY_SCAN", privacyOk)
        return result
    }

    /** Non-exiting entry point shared with the instrumented runner. */
    fun run(): Pair<String, Boolean> {
        val checks = checks(File("."), structural = true, manifest = true, pinned = true)
        val sb = StringBuilder()
        for (c in checks) sb.append("CHECK").append('\t').append(c.id).append('\t')
            .append(if (c.ok) "PASS" else "FAIL").append(NL)
        val failed = checks.count { !it.ok }
        sb.append("FIRST").append('\t').append(checks.firstOrNull { !it.ok }?.id ?: "NONE").append(NL)
        sb.append("SUMMARY").append("\tchecks=").append(checks.size)
            .append("\tpass=").append(checks.size - failed).append("\tfail=").append(failed).append(NL)
        return Pair(sb.toString(), failed == 0)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) { print("USAGE\tgenerate|verify\t<dir>" + NL); exitProcess(2) }
        val dir = File(args.getOrElse(1) { "." })
        if (args[0] == "generate") {
            regenerate(dir)
            print("GENERATE\tPASS\tfiles=" + FILE_NAMES.size + NL)
            return
        }
        if (args[0] != "verify") { print("BAD_MODE" + NL); exitProcess(2) }
        val checks = checks(dir, structural = true, manifest = true, pinned = true)
        val sb = StringBuilder()
        for (c in checks) sb.append("CHECK").append('\t').append(c.id).append('\t')
            .append(if (c.ok) "PASS" else "FAIL").append(NL)
        val failed = checks.count { !it.ok }
        sb.append("FIRST").append('\t').append(checks.firstOrNull { !it.ok }?.id ?: "NONE").append(NL)
        sb.append("SUMMARY").append("\tchecks=").append(checks.size)
            .append("\tpass=").append(checks.size - failed).append("\tfail=").append(failed).append(NL)
        print(sb.toString())
        exitProcess(if (failed == 0) 0 else 1)
    }
}

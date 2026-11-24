package com.devtunasoft.tuneros.qa71

import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * QA-71 shared, Android-free contract. The generator, strict parsers and reference model live in
 * one place so the host gate and the instrumented mirror derive byte-identical scratch data.
 * No provider address, credential, personal code, protected-media material or private
 * media text is stored here.
 */
object Qa71Contract {
    const val SCHEMA = "qa71-catalog-v1"
    const val SEED = 71290411L
    const val ROWS = 12
    const val CASES = 24
    const val SMALL_LIMIT = 5
    const val MAX_LIMIT = 500
    const val MARK_LENGTH = 12
    const val PINNED_CASES_SHA = "6184cb0fb70a8607f337fb6ac3608b7a034ad2afb50d3e31f15721d735e9101c"

    val FILES = listOf("cases.tsv", "corrupt.tsv", "dimensions.txt", "expected.txt")
    private val OPS = setOf("insert", "protect", "finish", "interrupt", "recent")

    fun deriveMark(): String = sha256(("$SEED-qa71-scratch").toByteArray(Charsets.UTF_8)).take(MARK_LENGTH)

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = (b.toInt() and 0xFF).toString(16)
            if (v.length == 1) out.append('0')
            out.append(v)
        }
        return out.toString()
    }

    private data class Row(
        val id: String,
        var state: String,
        var started: Long?,
        var protected: Boolean,
        var stopped: Long? = null,
    )

    /** Independent reference model: derives fixture expectations without calling production. */
    private class Model {
        val rows = LinkedHashMap<String, Row>()

        fun apply(op: String, f: List<String>): String {
            when (op) {
                "insert" -> {
                    if (rows.containsKey(f[0])) return "IGNORED"
                    rows[f[0]] = Row(f[0], "RECORDING", if (f[3] == "-") null else f[3].toLong(), false)
                    return "ADMITTED"
                }
                "protect" -> {
                    val row = rows[f[0]] ?: return "0"
                    row.protected = true
                    return "1"
                }
                "finish" -> {
                    val row = rows[f[0]] ?: return "0"
                    row.state = f[1]
                    row.stopped = f[2].toLong()
                    return "1"
                }
                "interrupt" -> {
                    var count = 0
                    for (row in rows.values) {
                        if (row.state == "RECORDING") {
                            row.state = "PARTIAL"
                            row.stopped = f[0].toLong()
                            count++
                        }
                    }
                    return count.toString()
                }
                "recent" -> {
                    val limit = f[0].toIntOrNull() ?: return "REJECT"
                    if (limit !in 1..MAX_LIMIT) return "REJECT"
                    val ordered = rows.values.sortedWith(
                        compareByDescending<Row> { it.started ?: 0L }.thenBy { it.id },
                    )
                    return ordered.take(limit).joinToString(",") { it.id }
                }
                else -> throw IllegalArgumentException("unsupported op")
            }
        }
    }

    private fun startOf(i: Int): Long? = if (i % 5 == 4) null else 1_760_000_000L + i * 60L

    /** Deterministic scratch cases plus their independent expected values. */
    fun cases(): Pair<List<List<String>>, List<Pair<String, String>>> {
        val model = Model()
        val cases = ArrayList<List<String>>()
        val expected = ArrayList<Pair<String, String>>()
        var n = 0
        fun add(op: String, vararg fields: String) {
            n++
            val id = "qa71c%03d".format(n)
            val padded = fields.toList() + List(5 - fields.size) { "-" }
            val result = model.apply(op, padded)
            cases.add(listOf(id, op) + padded + result)
            expected.add(id to result)
        }
        for (i in 0 until ROWS) {
            val start = startOf(i)
            add(
                "insert", "qa71r%03d".format(i), "Title-%03d".format(i),
                "ch-%02d".format(i % 7), start?.toString() ?: "-",
            )
        }
        add("protect", "qa71r003")
        add("finish", "qa71r001", "COMPLETED", "1760001000", "4096")
        add("finish", "qa71r005", "FAILED", "1760001200", "0")
        add("insert", "qa71r003", "Should-Not-Overwrite", "ch-99", "1999999999")
        add("interrupt", "1760002000")
        add("interrupt", "1760003000")
        add("recent", SMALL_LIMIT.toString())
        add("recent", MAX_LIMIT.toString())
        add("recent", "0")
        add("recent", (MAX_LIMIT + 1).toString())
        add("recent", "1")
        add("recent", "100")
        check(n == CASES) { "derived case count $n != $CASES" }
        return cases to expected
    }

    private fun rowsToText(header: String, rows: List<List<String>>): String =
        (listOf(header) + rows.map { it.joinToString("\t") }).joinToString("\n", postfix = "\n")

    /** Byte-exact fixture regeneration. Known-safe synthetic values only. */
    fun generate(): Map<String, String> {
        val (cases, expected) = cases()
        val dimensions = listOf(
            "schema=$SCHEMA",
            "seed=$SEED",
            "row_count=$ROWS",
            "case_count=$CASES",
            "small_limit=$SMALL_LIMIT",
            "max_limit=$MAX_LIMIT",
            "mark_length=$MARK_LENGTH",
        ).joinToString("\n", postfix = "\n")
        val corruptRows = listOf(
            listOf("qa71x001", "insert|bad!id|Title|ch-01|1760000000|-|ADMITTED"),
            listOf("qa71x002", "drop|qa71r001|-|-|-|-|ADMITTED"),
            listOf("qa71x003", "insert|qa71r099|Title|ch-01|1760000000|-|ADMITTED|extra"),
            listOf("qa71x004", "recent|-1|-|-|-|-|REJECT"),
            listOf("qa71x005", "insert|qa71r098|Title|ch-01|notnum|-|ADMITTED"),
            listOf("qa71x006", ""),
        )
        val out = LinkedHashMap<String, String>()
        out["cases.tsv"] = rowsToText(
            "case_id\top\tf1\tf2\tf3\tf4\tf5\texpected", cases,
        )
        out["expected.txt"] = rowsToText(
            "case_id\texpected", expected.map { listOf(it.first, it.second) },
        )
        out["corrupt.tsv"] = rowsToText("case_id\tpayload", corruptRows)
        out["dimensions.txt"] = dimensions
        val manifest = FILES.sorted().map { name ->
            val bytes = out.getValue(name).toByteArray(Charsets.UTF_8)
            sha256(bytes) + "\t" + name
        }
        out["sha256.txt"] = manifest.joinToString("\n", postfix = "\n")
        out[".gitattributes"] = "* -text\n.gitattributes -text\n"
        return out
    }

    fun parseDimensions(text: String): Map<String, String>? {
        val map = LinkedHashMap<String, String>()
        for (raw in text.split("\n").dropLastWhile { it.isEmpty() }) {
            val at = raw.indexOf('=')
            if (at <= 0 || at == raw.length - 1 || raw.contains('\t')) return null
            val key = raw.substring(0, at)
            val value = raw.substring(at + 1)
            if (!key.all { it.isLetterOrDigit() || it == '_' }) return null
            if (map.put(key, value) != null) return null
        }
        return map
    }

    /** Strict split that preserves trailing empty fields (K2 rejects split(limit = -1)). */
    private fun strictSplit(value: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in value.indices) {
            if (value[i] == delimiter) {
                out += value.substring(start, i)
                start = i + 1
            }
        }
        out += value.substring(start)
        return out
    }

    fun parseManifest(text: String): Map<String, String>? {
        val map = LinkedHashMap<String, String>()
        for (raw in text.split("\n").dropLastWhile { it.isEmpty() }) {
            val parts = strictSplit(raw, '\t')
            if (parts.size != 2 || parts[0].length != 64) return null
            if (!parts[0].all { it in '0'..'9' || it in 'a'..'f' }) return null
            if (map.put(parts[1], parts[0]) != null) return null
        }
        return map
    }

    /** Strict 8-field case row; returns null for anything outside the pinned shape. */
    fun parseCaseRow(line: String): List<String>? {
        val f = strictSplit(line, '\t')
        if (f.size != 8) return null
        if (!f[0].matches(Regex("qa71c[0-9]{3}"))) return null
        if (f[1] !in OPS) return null
        for (index in 2..6) if (f[index].isEmpty() || f[index].contains(' ')) return null
        when (f[1]) {
            "insert" -> {
                if (!f[2].matches(Regex("qa71r[0-9]{3}"))) return null
                if (f[5] != "-" && f[5].toLongOrNull() == null) return null
            }
            "protect", "finish" -> {
                if (!f[2].matches(Regex("qa71r[0-9]{3}"))) return null
                if (f[1] == "finish") {
                    if (f[3] !in setOf("COMPLETED", "FAILED", "PARTIAL")) return null
                    if (f[4].toLongOrNull() == null || f[5].toLongOrNull() == null) return null
                }
            }
            "interrupt" -> if (f[2].toLongOrNull() == null) return null
            "recent" -> if (f[2].toIntOrNull() == null) return null
        }
        if (f[7] != "ADMITTED" && f[7] != "IGNORED" && f[7] != "REJECT" &&
            !f[7].matches(Regex("qa71r[0-9]{3}(,qa71r[0-9]{3})*")) && f[7] != "0" && f[7] != "1" &&
            !f[7].matches(Regex("[0-9]+"))
        ) return null
        return f
    }

    fun parseCases(text: String): List<List<String>>? {
        val lines = text.split("\n").dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != "case_id\top\tf1\tf2\tf3\tf4\tf5\texpected") return null
        val rows = lines.drop(1).map { parseCaseRow(it) ?: return null }
        return rows
    }

    fun parseExpected(text: String): Map<String, String>? {
        val lines = text.split("\n").dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != "case_id\texpected") return null
        val map = LinkedHashMap<String, String>()
        for (raw in lines.drop(1)) {
            val f = strictSplit(raw, '\t')
            if (f.size != 2 || !f[0].matches(Regex("qa71c[0-9]{3}")) || f[1].isEmpty()) return null
            if (map.put(f[0], f[1]) != null) return null
        }
        return map
    }

    /** Corrupt rows use '|' so a tab-bearing malformed case can be represented safely. */
    fun parseCorrupt(text: String): List<String>? {
        val lines = text.split("\n").dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != "case_id\tpayload") return null
        val payloads = ArrayList<String>()
        for (raw in lines.drop(1)) {
            val f = strictSplit(raw, '\t')
            if (f.size != 2 || !f[0].matches(Regex("qa71x[0-9]{3}"))) return null
            payloads.add(f[1].replace("|", "\t"))
        }
        return payloads
    }

    /** Android-free, JVM-runnable mirror checks. */
    fun run(): Pair<String, Boolean> {
        val out = ArrayList<String>()
        var pass = 0
        var fail = 0
        fun check(code: String, ok: Boolean) {
            if (ok) pass++ else fail++
            out.add((if (ok) "PASS" else "FAIL") + "\t" + code)
        }
        val files = generate()
        check("MIRROR_SCHEMA", files.getValue("dimensions.txt").contains("schema=$SCHEMA"))
        check("MIRROR_CASE_COUNT", parseCases(files.getValue("cases.tsv"))?.size == CASES)
        check("MIRROR_EXPECTED_COUNT", parseExpected(files.getValue("expected.txt"))?.size == CASES)
        check("MIRROR_MANIFEST", parseManifest(files.getValue("sha256.txt"))?.size == FILES.size)
        check("MIRROR_CORRUPT_REJECT", parseCorrupt(files.getValue("corrupt.tsv"))?.all { parseCaseRow(it) == null } == true)
        val mirrorExpected = cases().second
        check(
            "MIRROR_ORDER",
            mirrorExpected[18].second.split(',').size == SMALL_LIMIT &&
                mirrorExpected[19].second.split(',').size == ROWS &&
                mirrorExpected[20].second == "REJECT" &&
                mirrorExpected[21].second == "REJECT" &&
                mirrorExpected[22].second.split(',').size == 1,
        )
        val first = sha256(files.getValue("cases.tsv").toByteArray(Charsets.UTF_8))
        check("MIRROR_DIGEST_SHAPE", first.length == 64 && first.all { it in '0'..'9' || it in 'a'..'f' })
        check("MIRROR_MARK_SHAPE", deriveMark().length == MARK_LENGTH)
        out.add("SUMMARY\tchecks=8\tpass=$pass\tfail=$fail")
        return out.joinToString("\n", postfix = "\n") to (fail == 0)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val (text, ok) = run()
        print(text)
        exitProcess(if (ok) 0 else 1)
    }
}

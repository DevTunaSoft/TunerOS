package com.devtunasoft.tuneros.qa64

import com.devtunasoft.tuneros.core.TimelineEntry
import com.devtunasoft.tuneros.core.TimelineLayout
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * QA-64 host gate. Compiles the real pure-Kotlin TimelineLayout and Models sources and drives
 * TimelineLayout.widthUnits from a byte-exact scratch fixture. Diagnostics contain only check ids,
 * counts, digests and exception class names.
 */
object Qa64HostMain {
    private class Recorder {
        val lines = ArrayList<String>()
        var failures = 0
        var first = "NONE"

        fun check(id: String, ok: Boolean, detail: String = "") {
            lines += "CHECK\t$id\t" + (if (ok) "PASS" else "FAIL") + "\t$detail"
            if (!ok) {
                failures++
                if (first == "NONE") first = id
            }
        }
    }

    private class Case(
        val id: String, val family: String, val start: Long, val end: Long,
        val units: Int, val expected: Int, val note: String,
    )

    private fun sha256(bytes: ByteArray): String = buildString(64) {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(Character.forDigit(value ushr 4, 16))
            append(Character.forDigit(value and 0x0f, 16))
        }
    }

    private fun linesOf(bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.UTF_8)
        val parts = text.split("\n")
        return if (text.endsWith("\n")) parts.dropLast(1) else parts
    }

    private fun parseDimensions(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in lines) {
            val at = line.indexOf('=')
            require(at > 0 && at == line.lastIndexOf('=')) { "dimension-field" }
            val key = line.substring(0, at)
            require(!out.containsKey(key)) { "dimension-duplicate" }
            out[key] = line.substring(at + 1)
        }
        return out
    }

    private fun parseCases(lines: List<String>): List<Case> {
        val out = ArrayList<Case>(lines.size - 1)
        for (line in lines.drop(1)) {
            val f = line.split("\t")
            require(f.size == 7) { "case-field-count" }
            require(Regex("c[0-9]{4}").matches(f[0])) { "case-id" }
            val start = f[2].toLongOrNull() ?: throw IllegalArgumentException("case-start")
            val end = f[3].toLongOrNull() ?: throw IllegalArgumentException("case-end")
            val units = f[4].toIntOrNull() ?: throw IllegalArgumentException("case-units")
            val expected = f[5].toIntOrNull() ?: throw IllegalArgumentException("case-expected")
            require(f[6].matches(Regex("[a-z]+-scratch-[0-3]"))) { "case-note" }
            out += Case(f[0], f[1], start, end, units, expected, f[6])
        }
        return out
    }

    private fun parseManifest(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in lines) {
            val parts = line.split("  ", limit = 2)
            require(parts.size == 2 && parts[0].length == 64) { "manifest-field" }
            require(!out.containsKey(parts[1])) { "manifest-duplicate" }
            out[parts[1]] = parts[0]
        }
        return out
    }

    private fun forbidden(text: String): List<String> {
        val lower = text.lowercase()
        val patterns = listOf(
            "http://", "https://", "ftp://", "www.", "password", "passwd", "pwd=",
            "secret", "token", "pin=", "credential", "apikey", "api_key", "auth=",
            "recording-payload", ".m3u8",
        )
        return patterns.filter { lower.contains(it) }
    }

    private fun containsMarker(text: String): Boolean {
        val markerHit = text.contains(Qa64Assertions.marker())
        return markerHit
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun finish(rec: Recorder, checks: Int): Nothing {
        rec.lines += "SUMMARY\tchecks=$checks\tfail=${rec.failures}\tfirst=${rec.first}"
        rec.lines += "VERDICT\t" + (if (rec.failures == 0) "PASS" else "FAIL")
        rec.lines.forEach(::println)
        exitProcess(if (rec.failures == 0) 0 else 1)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("usage: Qa64HostMain generate|verify|gate-only|golden <fixture-dir>")
            exitProcess(2)
        }
        val mode = args[0]
        val dir = File(args[1])
        if (mode == "generate") {
            Qa64Assertions.generate(dir)
            val total = dir.listFiles()!!.sumOf { it.length() }
            println("GENERATE\tPASS\tfiles=${dir.listFiles()!!.size}\tbytes=$total")
            return
        }
        val skip = if (mode == "golden") setOf("BYTE_IDENTITY", "PINNED_DIGEST") else emptySet()
        val rec = Recorder()
        fun check(id: String, ok: Boolean, detail: String = "") {
            if (id !in skip) rec.check(id, ok, detail)
        }

        val names = listOf("dimensions.tsv", "cases.tsv", "corrupt.tsv", "sha256.txt", ".gitattributes")
        val bytes = HashMap<String, ByteArray>()
        var readOk = true
        for (name in names) {
            val file = File(dir, name)
            if (!file.isFile) {
                readOk = false
                check("ROW_BOUNDS", false, "missing=$name")
                continue
            }
            bytes[name] = file.readBytes()
        }
        if (!readOk) finish(rec, rec.lines.count { it.startsWith("CHECK\t") })

        val dataLines = HashMap<String, List<String>>()
        for (name in listOf("dimensions.tsv", "cases.tsv", "corrupt.tsv", "sha256.txt")) {
            dataLines[name] = linesOf(bytes[name]!!)
        }
        val crCount = names.sumOf { name -> bytes[name]!!.count { it == 13.toByte() } }
        val dimensionsHeaderOk = dataLines["dimensions.tsv"]!!.isNotEmpty() &&
            dataLines["dimensions.tsv"]!![0] == "schema=" + Qa64Assertions.SCHEMA
        val casesHeaderOk = dataLines["cases.tsv"]!!.firstOrNull() ==
            "case_id\tfamily\tstart\tend\tunits\texpected\tnote"
        val corruptHeaderOk = dataLines["corrupt.tsv"]!!.firstOrNull() ==
            "variant\tstart\tend\tunits"
        check("HEADER_STRICT", dimensionsHeaderOk && casesHeaderOk && corruptHeaderOk,
            "dimensions=$dimensionsHeaderOk cases=$casesHeaderOk corrupt=$corruptHeaderOk")

        val caseRows = dataLines["cases.tsv"]!!.drop(1)
        val corruptRows = dataLines["corrupt.tsv"]!!.drop(1)
        val boundsOk = crCount == 0 && caseRows.size == Qa64Assertions.CASES &&
            corruptRows.size == Qa64Assertions.CORRUPT &&
            caseRows.all { it.count { ch -> ch == '\t' } == 6 } &&
            corruptRows.all { it.count { ch -> ch == '\t' } == 3 }
        check("ROW_BOUNDS", boundsOk, "cases=${caseRows.size} corrupt=${corruptRows.size} cr=$crCount")

        val generated = Qa64Assertions.dataFiles()
        check("BYTE_IDENTITY", generated.all { (name, expected) -> bytes[name]!!.contentEquals(expected) } &&
            bytes[".gitattributes"]!!.contentEquals(Qa64Assertions.attributesText().toByteArray(Charsets.UTF_8)),
            "files=${generated.size}")

        val manifest = runCatching { parseManifest(dataLines["sha256.txt"]!!) }.getOrNull()
        val manifestOk = manifest != null && manifest.size == 3 &&
            manifest.keys == generated.keys.toSet() &&
            manifest.all { (name, hash) -> hash == sha256(bytes[name]!!) }
        check("MANIFEST_CONSISTENT", manifestOk, "files=${manifest?.size ?: 0}")

        check("PINNED_DIGEST", bytes["sha256.txt"]!!.contentEquals(
            Qa64Assertions.manifestText(generated).toByteArray(Charsets.UTF_8)), "mode=$mode")

        val dims = runCatching { parseDimensions(dataLines["dimensions.tsv"]!!) }.getOrNull()
        val cases = runCatching { parseCases(dataLines["cases.tsv"]!!) }.getOrNull()
        if (dims == null || cases == null) {
            check("PARSE_FAIL", false, "dimensions=${dims != null} cases=${cases != null}")
            finish(rec, rec.lines.count { it.startsWith("CHECK\t") })
        }

        fun produced(case: Case): Int = runCatching {
            TimelineLayout.widthUnits(TimelineEntry(case.start, case.end), case.units)
        }.getOrDefault(Int.MIN_VALUE)

        val floorCases = cases.filter { case ->
            (case.end - case.start) * case.units.toLong() / 3600L == 0L
        }
        check("FLOOR_CASE", floorCases.size >= 3 &&
            floorCases.all { it.expected == 1 && produced(it) == 1 }, "count=${floorCases.size}")

        val saturated = cases.filter { it.expected == Int.MAX_VALUE }
        check("SATURATION_CASE", saturated.size >= 2 &&
            saturated.all { produced(it) == Int.MAX_VALUE }, "count=${saturated.size}")

        val scaleExpected = cases.filter { it.family == "scale" }.map { it.expected }
        check("SCALE_CASES", scaleExpected == listOf(120, 240, 480, 60), "count=${scaleExpected.size}")

        val wrong = cases.count { produced(it) != it.expected }
        check("CONTRACT_WIDTHS", cases.size == Qa64Assertions.CASES && wrong == 0,
            "rows=${cases.size} wrong=$wrong")

        val familyCounts = cases.groupBy { it.family }.mapValues { it.value.size }
        val capsOk = dims["cases"] == Qa64Assertions.CASES.toString() &&
            dims["families"] == Qa64Assertions.FAMILIES.toString() &&
            dims["corrupt"] == Qa64Assertions.CORRUPT.toString() &&
            dims["cycles"] == Qa64Assertions.CYCLES.toString() &&
            familyCounts.size == Qa64Assertions.FAMILIES && familyCounts.values.all { it == 4 }
        check("CAP_BINDING", capsOk, "families=${familyCounts.size}")

        var rejected = 0
        for (row in corruptRows) {
            val refused = runCatching {
                val f = row.split("\t")
                require(f.size == 4) { "corrupt-field-count" }
                val start = f[1].toLongOrNull() ?: throw IllegalArgumentException("corrupt-start")
                val end = f[2].toLongOrNull() ?: throw IllegalArgumentException("corrupt-end")
                val units = f[3].toIntOrNull() ?: throw IllegalArgumentException("corrupt-units")
                TimelineLayout.widthUnits(TimelineEntry(start, end), units)
                false
            }.exceptionOrNull() is IllegalArgumentException
            if (refused) rejected++
        }
        check("CORRUPT_REJECT", rejected == Qa64Assertions.CORRUPT, "rejected=$rejected")

        val threadsBefore = Thread.getAllStackTraces().keys.size
        val journals = HashSet<String>()
        var firstHeap = 0L
        var maxHeap = 0L
        for (cycle in 0 until Qa64Assertions.CYCLES) {
            val widths = cases.map {
                TimelineLayout.widthUnits(TimelineEntry(it.start, it.end), it.units)
            }
            journals += sha256(widths.joinToString(",").toByteArray(Charsets.UTF_8))
            if (cycle == 0) {
                System.gc()
                Thread.sleep(20L)
                firstHeap = usedHeap()
            }
            maxHeap = maxOf(maxHeap, usedHeap())
        }
        val threadsAfter = Thread.getAllStackTraces().keys.size
        val qaThreads = Thread.getAllStackTraces().keys.count { it.name.startsWith("qa64-") }
        val mutableStatics = TimelineLayout::class.java.declaredFields.count { field ->
            Modifier.isStatic(field.modifiers) &&
                (MutableCollection::class.java.isAssignableFrom(field.type) ||
                    MutableMap::class.java.isAssignableFrom(field.type) || field.type.isArray)
        }
        check("NO_STATE", journals.size == 1 && mutableStatics == 0,
            "journals=${journals.size} statics=$mutableStatics")
        check("BOUNDS", maxHeap <= firstHeap + 2_000_000L &&
            threadsAfter == threadsBefore && qaThreads == 0,
            "heap=$maxHeap base=$firstHeap threads=$threadsAfter/$threadsBefore qa=$qaThreads")

        val journalled = sha256(cases.joinToString(",") { it.expected.toString() }.toByteArray(Charsets.UTF_8))
        check("GOLDEN_JOURNAL", dims["golden"] == journalled, "digest=${journalled.take(12)}")

        val scanned = names.joinToString("\n") { String(bytes[it]!!, Charsets.UTF_8) } +
            "\n" + String(File(dir, "README.md").readBytes(), Charsets.UTF_8)
        val offenders = forbidden(rec.lines.joinToString("\n") + "\n" + scanned)
        val leakControl = containsMarker("qa64 diagnostic " + Qa64Assertions.marker())
        check("PRIVACY_SCAN", offenders.isEmpty() && leakControl && !containsMarker(scanned),
            "offenders=${offenders.size} control=$leakControl")

        val prefixes = listOf("CHECK", "SUMMARY", "VERDICT", "BOUNDS", "DIAGNOSTIC", "GENERATE")
        val diagnosticOk = rec.lines.all { line ->
            prefixes.any { line.startsWith(it + "\t") } && line.length <= 240
        }
        check("DIAGNOSTIC_SCAN", diagnosticOk, "lines=${rec.lines.size}")

        rec.lines += "BOUNDS\theap=$maxHeap\tlimit=100663296\tthreads=$threadsAfter\tbase=$threadsBefore"
        val checks = rec.lines.count { it.startsWith("CHECK\t") }
        finish(rec, checks)
    }
}

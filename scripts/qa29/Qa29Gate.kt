package com.devtunasoft.tuneros.qa29

import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.RecordingState
import java.io.File
import kotlin.system.exitProcess

/** A bounded first-failure signal. The runner pins the code of each RED control. */
class Refuse(val code: String, val note: String) : Exception("$code: $note")

private data class Case(val id: String, val requested: String, val bytes: Long, val expected: String, val family: String)

object Qa29Gate {
    private var checks = 0
    private var passes = 0

    fun counts(): Pair<Int, Int> = checks to passes

    private fun requireCheck(code: String, ok: Boolean, note: String = "") {
        checks++
        if (!ok) {
            println("CHECK\tFAIL\t$code\t$note")
            throw Refuse(code, note)
        }
        passes++
        println("CHECK\tPASS\t$code\t$note")
    }

    private fun write(file: File, text: String) {
        file.writeText(text, Charsets.UTF_8)
    }

    private fun caseRows(): List<String> {
        val out = ArrayList<String>(Qa29Contract.EXPECTED_CASES)
        var n = 0
        fun add(family: String, requested: String, bytes: Long, expected: String) {
            n++
            val id = "qa29-$family-" + n.toString().padStart(4, '0')
            out.add("$id\t$requested\t$bytes\t$expected\t$family")
        }
        for (i in 0 until 8) add("F1", "COMPLETED", 0L, "FAILED")
        for (bytes in longArrayOf(1L, 2L, 7L, 64L, 65535L, 65536L, 1048576L, Long.MAX_VALUE)) {
            add("F2", "COMPLETED", bytes, "COMPLETED")
        }
        for (state in listOf("SCHEDULED", "RECORDING", "PARTIAL", "FAILED", "CANCELLED")) {
            for (bytes in longArrayOf(0L, 1L)) add("F3", state, bytes, state)
        }
        for (bytes in longArrayOf(1L, 1023L, 1024L, 65535L, 65536L, 1L shl 40)) {
            add("F4", "COMPLETED", bytes, "COMPLETED")
        }
        for (state in listOf("COMPLETED", "SCHEDULED", "FAILED")) {
            for (bytes in longArrayOf(-1L, -2L, Long.MIN_VALUE)) add("F5", state, bytes, "REJECT")
        }
        for (state in RecordingState.values()) {
            for (bytes in longArrayOf(0L, 1L, 4096L)) {
                val expected = if (state == RecordingState.COMPLETED && bytes == 0L) "FAILED" else state.name
                add("F6", state.name, bytes, expected)
            }
        }
        return out
    }

    private fun corruptRows(): List<String> = listOf(
        "C01_TRUNCATED~qa29-X-0001~COMPLETED~0",
        "C02_OVERLONG~" + "x".repeat(120) + "~COMPLETED~1~COMPLETED~F1",
        "C03_BADFIELDS~qa29-X-0002~COMPLETED~1~COMPLETED~F1~EXTRA",
        "C04_BADSTATE~qa29-X-0003~BOGUS~1~COMPLETED~F1",
        "C05_BADBYTES~qa29-X-0004~COMPLETED~notnum~COMPLETED~F1",
        "C06_BADEXPECT~qa29-X-0005~COMPLETED~1~BOGUS~F1",
        "C07_EMPTY~",
        "C08_TRAILING~qa29-X-0006~COMPLETED~1~COMPLETED~F1~",
    )

    fun generate(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) throw Refuse("GENERATE_DIR", dir.path)
        val cases = (listOf(Qa29Contract.CASES_HEADER) + caseRows()).joinToString("\n", postfix = "\n")
        val corrupt = (listOf(Qa29Contract.CORRUPT_HEADER) + corruptRows()).joinToString("\n", postfix = "\n")
        val dims = listOf(
            "seed\t${Qa29Contract.SEED}",
            "cases\t${Qa29Contract.EXPECTED_CASES}",
            "families\t${Qa29Contract.FAMILY_COUNT}",
            "line_cap\t${Qa29Contract.LINE_CAP}",
            "case_cap\t${Qa29Contract.CASE_CAP}",
            "min_cases\t${Qa29Contract.MIN_CASES}",
        ).joinToString("\n", postfix = "\n")
        val contractText = listOf(
            "QA-29 bounded DVR final-state transition scratch fixture.",
            "All ids and tokens are symbolic. Every value is derived from a fixed seed.",
            "",
            "F1 COMPLETED with zero bytes is terminal FAILED.",
            "F2 COMPLETED with a positive byte count stays COMPLETED.",
            "F3 every non-COMPLETED state is preserved at zero and positive bytes.",
            "F4 positive byte counts at segment boundaries stay COMPLETED.",
            "F5 a negative byte count is rejected before any state decision.",
            "F6 all six terminal and non-terminal states are covered exhaustively.",
            "",
            "corrupt.tsv carries bounded malformed rows that must be rejected.",
            "",
        ).joinToString("\n")
        write(File(dir, "cases.tsv"), cases)
        write(File(dir, "corrupt.tsv"), corrupt)
        write(File(dir, "dimensions.tsv"), dims)
        write(File(dir, "contract.txt"), contractText)
        val manifest = Qa29Contract.DATA_FILES.filter { it != "sha256.txt" }.joinToString("\n") { name ->
            "$name\t${Qa29Contract.sha(File(dir, name).readBytes())}"
        } + "\n"
        write(File(dir, "sha256.txt"), manifest)
        val bytes = File(dir, "cases.tsv").length()
        val sha = Qa29Contract.sha(File(dir, "cases.tsv").readBytes())
        println("GENERATE\tPASS\tcases=${Qa29Contract.EXPECTED_CASES}\tbytes=$bytes\tsha=$sha")
    }

    private fun parseCase(line: String): Case? {
        if (line.isBlank() || line.length > Qa29Contract.LINE_CAP) return null
        val f = line.split('\t', limit = 0)
        if (f.size != 5) return null
        if (f[0].isBlank() || !f[0].startsWith("qa29-")) return null
        if (f[1] !in Qa29Contract.STATES || f[4] !in Qa29Contract.FAMILIES) return null
        val bytes = f[2].toLongOrNull() ?: return null
        if (f[3] !in Qa29Contract.STATES && f[3] != "REJECT") return null
        return Case(f[0], f[1], bytes, f[3], f[4])
    }

    private fun privacyOffender(dir: File): String {
        for (name in Qa29Contract.DATA_FILES) {
            val text = File(dir, name).readText(Charsets.UTF_8)
            if (text.contains(Qa29Contract.MARKER)) return "$name:MARKER"
            for (pattern in Qa29Contract.BAD_PATTERNS) {
                if (pattern.containsMatchIn(text)) return "$name:${pattern.pattern.take(20)}"
            }
        }
        return ""
    }

    fun verify(dir: File) {
        for (name in Qa29Contract.DATA_FILES) {
            requireCheck("FIXTURE_PRESENT", File(dir, name).isFile, name)
        }
        val caseFile = File(dir, "cases.tsv")
        val caseLines = caseFile.readLines(Charsets.UTF_8)
        requireCheck("HEADER_STRICT", caseLines.firstOrNull() == Qa29Contract.CASES_HEADER)

        val dataLines = caseLines.drop(1)
        requireCheck(
            "FIELD_BOUNDS",
            dataLines.all { it.split('\t', limit = 0).size == 5 && it.length <= Qa29Contract.LINE_CAP },
            "rows=${dataLines.size}",
        )
        requireCheck(
            "STATE_BOUNDS",
            dataLines.all {
                val f = it.split('\t', limit = 0)
                f[1] in Qa29Contract.STATES && f[4] in Qa29Contract.FAMILIES
            },
        )
        requireCheck(
            "BYTE_BOUNDS",
            dataLines.all { it.split('\t', limit = 0)[2].toLongOrNull() != null },
        )
        requireCheck(
            "EXPECT_BOUNDS",
            dataLines.all {
                val e = it.split('\t', limit = 0)[3]
                e in Qa29Contract.STATES || e == "REJECT"
            },
        )
        requireCheck(
            "CASE_CAP",
            dataLines.size in Qa29Contract.MIN_CASES..Qa29Contract.CASE_CAP &&
                Qa29Contract.FAMILIES.all { f -> dataLines.any { line -> line.endsWith("\t$f") } },
            "rows=${dataLines.size}",
        )

        val dims = File(dir, "dimensions.tsv").readLines(Charsets.UTF_8).associate { line ->
            val p = line.split('\t', limit = 0)
            p[0] to p.getOrElse(1) { "" }
        }
        requireCheck(
            "DIMENSIONS",
            dims["seed"] == Qa29Contract.SEED.toString() &&
                dims["cases"] == dataLines.size.toString() &&
                dims["families"] == Qa29Contract.FAMILY_COUNT.toString() &&
                dims["line_cap"] == Qa29Contract.LINE_CAP.toString() &&
                dims["case_cap"] == Qa29Contract.CASE_CAP.toString() &&
                dims["min_cases"] == Qa29Contract.MIN_CASES.toString(),
            "keys=${dims.size}",
        )

        val offender = privacyOffender(dir)
        requireCheck("PRIVACY_FIXTURE", offender.isEmpty(), offender)

        val sha = Qa29Contract.sha(caseFile.readBytes())
        requireCheck("BYTE_IDENTITY", sha == Qa29Contract.CASES_SHA, "sha=$sha")

        val manifestLines = File(dir, "sha256.txt").readLines(Charsets.UTF_8)
        requireCheck(
            "MANIFEST_MATCH",
            manifestLines.size == Qa29Contract.DATA_FILES.size - 1 && manifestLines.all { line ->
                val p = line.split('\t', limit = 0)
                p.size == 2 && p[0] in Qa29Contract.DATA_FILES &&
                    p[1] == Qa29Contract.sha(File(dir, p[0]).readBytes())
            },
            "lines=${manifestLines.size}",
        )

        val corruptLines = File(dir, "corrupt.tsv").readLines(Charsets.UTF_8)
        requireCheck("CORRUPT_HEADER", corruptLines.firstOrNull() == Qa29Contract.CORRUPT_HEADER)
        val parsedCases = dataLines.mapNotNull { parseCase(it) }
        requireCheck("CASE_PARSE", parsedCases.size == dataLines.size, "parsed=${parsedCases.size}")
        for (line in corruptLines.drop(1)) {
            val idx = line.indexOf('~')
            val variant = if (idx < 0) "" else line.substring(0, idx)
            val payload = if (idx < 0) line else line.substring(idx + 1).replace('~', '\t')
            requireCheck("CORRUPT_REJECT", parseCase(payload) == null, variant)
        }

        val threadsBefore = Thread.activeCount()
        val codes = mapOf(
            "F1" to "ZERO_BYTE_GUARD",
            "F2" to "COMPLETED_POSITIVE",
            "F3" to "STATE_PRESERVE",
            "F4" to "BYTE_BOUNDARY",
            "F5" to "NEGATIVE_GUARD",
            "F6" to "EXHAUSTIVE_STATES",
        )
        for (family in Qa29Contract.FAMILIES) {
            val rows = parsedCases.filter { it.family == family }
            val bad = rows.firstOrNull { row ->
                val actual = try {
                    RecordingPolicy.finalState(RecordingState.valueOf(row.requested), row.bytes).name
                } catch (e: IllegalArgumentException) {
                    "REJECT"
                }
                actual != row.expected
            }
            requireCheck(codes.getValue(family), bad == null, bad?.id ?: "rows=${rows.size}")
        }
        val threadsAfter = Thread.activeCount()
        Runtime.getRuntime().gc()
        val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        requireCheck(
            "HEAP_THREADS_BOUNDED",
            used <= 48L * 1024 * 1024 && threadsAfter == threadsBefore,
            "used=$used threads=$threadsBefore/$threadsAfter",
        )
    }
}

fun main(args: Array<String>) {
    val mode = args.getOrElse(0) { "verify" }
    val dir = File(args.getOrElse(1) { "qa/fixtures/qa29" })
    if (mode == "generate") {
        Qa29Gate.generate(dir)
        return
    }
    try {
        Qa29Gate.verify(dir)
        val c = Qa29Gate.counts()
        println("SUMMARY\tchecks=${c.first}\tpass=${c.second}\tfail=0\tfirst=NONE")
    } catch (e: Refuse) {
        val c = Qa29Gate.counts()
        println("SUMMARY\tchecks=${c.first}\tpass=${c.second}\tfail=1\tfirst=${e.code}\tnote=${e.note}")
        exitProcess(1)
    } catch (e: Exception) {
        val c = Qa29Gate.counts()
        println("SUMMARY\tchecks=${c.first}\tpass=${c.second}\tfail=1\tfirst=UNEXPECTED\tnote=${e.javaClass.simpleName}")
        exitProcess(1)
    }
}

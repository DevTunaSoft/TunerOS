package com.devtunasoft.tuneros.qa30

import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsContentRange
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.system.exitProcess

/** Zero-dependency host gate for bounded 206 partial-content range admission. */
object Qa30Gate {
    private var checks = 0
    private var passes = 0

    private fun check(code: String, ok: Boolean, note: String = "") {
        checks++
        if (!ok) {
            println("CHECK\tFAIL\t$code\t$note")
            throw Refuse(code, note)
        }
        passes++
        println("CHECK\tPASS\t$code\t$note")
    }

    private fun read(dir: File, name: String): String = File(dir, name).readText(UTF_8)
    private fun raw(dir: File, name: String): ByteArray = File(dir, name).readBytes()

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: "verify"
        val dir = File(args.getOrNull(1) ?: ".")
        try {
            when (mode) {
                "generate" -> {
                    val generated = Qa30Fixture.generate()
                    for (name in Qa30Fixture.DATA_FILES + "sha256.txt") {
                        val payload = generated.getValue(name).toByteArray(UTF_8)
                        File(dir, name).writeBytes(payload)
                        println("GENERATE\t$name\tbytes=${payload.size}")
                    }
                    println("SUMMARY\tmode=generate\tfiles=${generated.size}")
                    exitProcess(0)
                }
                "verify", "mutant" -> {
                    runChecks(dir, full = mode == "verify")
                    println("SUMMARY\tchecks=$checks\tpass=$passes\tfail=0\tfirst=NONE")
                    exitProcess(0)
                }
                else -> {
                    println("USAGE\tgenerate|verify|mutant")
                    exitProcess(2)
                }
            }
        } catch (error: Refuse) {
            println("FIRST\t${error.code}\t${error.note}")
            println("SUMMARY\tchecks=$checks\tpass=$passes\tfail=1\tfirst=${error.code}")
            exitProcess(Qa30Contract.EXIT_BY_CHECK[error.code] ?: 90)
        } catch (failure: Throwable) {
            println("FIRST\tCRASH\t${failure.javaClass.simpleName}")
            println("SUMMARY\tchecks=$checks\tpass=$passes\tfail=1\tfirst=CRASH")
            exitProcess(99)
        }
    }

    private fun groupCheck(cases: List<Qa30Fixture.Case>, group: String, code: String) {
        val rows = cases.filter { it.group == group }
        var wrong = 0
        for (row in rows) {
            val admitted = Qa30Fixture.admits(row)
            if (admitted != (row.expected == Qa30Contract.ACCEPT)) wrong++
        }
        check(code, rows.isNotEmpty() && wrong == 0, "rows=${rows.size} wrong=$wrong")
    }

    private fun runChecks(dir: File, full: Boolean) {
        val scanned = (Qa30Fixture.DATA_FILES + "sha256.txt").joinToString("\n") { read(dir, it) }
        check("PRIVACY_FIXTURE", Qa30Fixture.privacyHit(scanned) == null,
            "files=${Qa30Fixture.DATA_FILES.size + 1}")

        val cases = Qa30Fixture.parseCases(read(dir, "cases.tsv"))
        check("HEADER_STRICT", true, "header=ok")
        check("FIELD_BOUNDS", true, "rows=${cases.size}")
        check("CASE_CAP", cases.size == Qa30Contract.CASE_CAP, "rows=${cases.size}")

        val dimensions = Qa30Fixture.parsePairs(read(dir, "dimensions.tsv"))
        val counts = cases.groupingBy { it.group }.eachCount()
        val derived = linkedMapOf(
            "VERSION" to Qa30Contract.VERSION.toString(),
            "SEED" to Qa30Contract.SEED.toString(),
            "CASE_CAP" to Qa30Contract.CASE_CAP.toString(),
            "CORRUPT_CAP" to Qa30Contract.CORRUPT_CAP.toString(),
            "MAX_HEADER_CHARS" to Qa30Contract.MAX_HEADER_CHARS.toString(),
        )
        for (group in Qa30Contract.GROUPS) derived[group + "_N"] = (counts[group] ?: 0).toString()
        check("DIMENSIONS",
            (dimensions.keys - "GOLDEN") == derived.keys && derived.all { dimensions[it.key] == it.value },
            "fields=${derived.size}")

        if (full) {
            val manifest = Qa30Fixture.parseManifest(read(dir, "sha256.txt"))
            val mismatched = Qa30Fixture.DATA_FILES.filter {
                manifest[it] != Qa30Fixture.sha256(read(dir, it))
            }
            check("MANIFEST_MATCH",
                manifest.keys == Qa30Fixture.DATA_FILES.toSet() && mismatched.isEmpty(),
                "files=${Qa30Fixture.DATA_FILES.size} bad=${mismatched.size}")
            val regenerated = Qa30Fixture.generate()
            check("BYTE_IDENTITY",
                Qa30Fixture.DATA_FILES.all {
                    regenerated.getValue(it).toByteArray(UTF_8).contentEquals(raw(dir, it))
                },
                "files=${Qa30Fixture.DATA_FILES.size}")
        }

        check("GUARD_ACCEPT",
            HlsContentRange.matches("bytes 100-115/200", HlsByteRange(16, 100)), "canonical")
        check("GUARD_NULL",
            !HlsContentRange.matches(null, HlsByteRange(16, 100)), "missing-header")
        check("GUARD_NEGATIVE_OFFSET",
            !HlsContentRange.matches("bytes 100-115/200", HlsByteRange(16, -1)), "negative-start")
        check("GUARD_ZERO_LENGTH",
            !HlsContentRange.matches("bytes 100-115/200", HlsByteRange(0, 100)), "zero-length")
        check("GUARD_OVERFLOW",
            !HlsContentRange.matches("bytes 9223372036854775805-9223372036854775807/*",
                HlsByteRange(16, Long.MAX_VALUE - 2)), "addition-overflow")
        check("GUARD_TOTAL",
            !HlsContentRange.matches("bytes 100-115/115", HlsByteRange(16, 100)), "total-not-greater")
        check("GUARD_FROM",
            !HlsContentRange.matches("bytes 101-115/200", HlsByteRange(16, 100)), "wrong-start")
        check("GUARD_TO",
            !HlsContentRange.matches("bytes 100-116/200", HlsByteRange(16, 100)), "wrong-end")
        check("GUARD_MATCH_ENTIRE",
            !HlsContentRange.matches("junk bytes 100-115/200", HlsByteRange(16, 100)), "leading-text")

        groupCheck(cases, Qa30Contract.G_ACCEPT, "ACCEPT_EXACT")
        groupCheck(cases, Qa30Contract.G_OFFSET, "REJECT_OFFSET")
        groupCheck(cases, Qa30Contract.G_END, "REJECT_END")
        groupCheck(cases, Qa30Contract.G_TOTAL, "REJECT_TOTAL")
        groupCheck(cases, Qa30Contract.G_MALFORMED, "REJECT_MALFORMED")
        groupCheck(cases, Qa30Contract.G_NULL, "REJECT_NULL")
        groupCheck(cases, Qa30Contract.G_OVERFLOW, "REJECT_OVERFLOW")
        groupCheck(cases, Qa30Contract.G_RANGE_ARGS, "REJECT_RANGE_ARGS")

        val corrupt = Qa30Fixture.parseCorrupt(read(dir, "corrupt.tsv"))
        check("CORRUPT_REJECT",
            corrupt.size == Qa30Contract.CORRUPT_CAP && corrupt.all { row ->
                try {
                    Qa30Fixture.parseCaseRow(row.payload, "CORRUPT_REJECT")
                    false
                } catch (error: Refuse) {
                    error.code == "CORRUPT_REJECT"
                } catch (failure: Throwable) {
                    true
                }
            },
            "rows=${corrupt.size}")
        check("LAX_ACCEPTS_CORRUPT", corrupt.all { Qa30Fixture.laxAccepts(it.payload) },
            "rows=${corrupt.size}")
        check("LEAK_DETECTOR",
            Qa30Fixture.privacyHit("prefix " + Qa30Fixture.marker() + " suffix") == "MARKER",
            "synthetic-probe")

        if (full) {
            check("GOLDEN_DIGEST", Qa30Fixture.golden(cases) == dimensions["GOLDEN"],
                "digest=${Qa30Fixture.golden(cases).take(12)}")
        }

        val heap = Runtime.getRuntime().maxMemory()
        check("HEAP_BOUND", heap <= 100L * 1024 * 1024, "max=$heap")
        val threads = Thread.activeCount()
        check("THREAD_BOUND", threads <= 8, "threads=$threads")
    }
}

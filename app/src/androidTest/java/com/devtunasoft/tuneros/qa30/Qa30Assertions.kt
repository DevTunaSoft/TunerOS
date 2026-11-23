package com.devtunasoft.tuneros.qa30

import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsContentRange
import kotlin.system.exitProcess

/** Android-free mirror of the QA-30 contract; compiles on the JVM and on the device. */
object Qa30Assertions {
    data class Check(val code: String, val ok: Boolean, val note: String = "")

    private fun add(out: MutableList<Check>, code: String, ok: Boolean, note: String = "") {
        out.add(Check(code, ok, note))
    }

    private fun group(out: MutableList<Check>, cases: List<Qa30Fixture.Case>, name: String, code: String) {
        val rows = cases.filter { it.group == name }
        var wrong = 0
        for (row in rows) {
            if (Qa30Fixture.admits(row) != (row.expected == Qa30Contract.ACCEPT)) wrong++
        }
        add(out, code, rows.isNotEmpty() && wrong == 0, "rows=${rows.size} wrong=$wrong")
    }

    fun mirror(): List<Check> {
        val out = ArrayList<Check>()
        val generated = Qa30Fixture.generate()
        val casesText = generated.getValue("cases.tsv")
        val scanned = (Qa30Fixture.DATA_FILES + "sha256.txt").joinToString("\n") { generated.getValue(it) }

        val cases = try {
            Qa30Fixture.parseCases(casesText)
        } catch (error: Refuse) {
            add(out, error.code, false, error.note)
            return out
        }

        add(out, "PRIVACY_FIXTURE", Qa30Fixture.privacyHit(scanned) == null,
            "files=${Qa30Fixture.DATA_FILES.size + 1}")
        add(out, "HEADER_STRICT", true, "header=ok")
        add(out, "FIELD_BOUNDS", true, "rows=${cases.size}")
        add(out, "CASE_CAP", cases.size == Qa30Contract.CASE_CAP, "rows=${cases.size}")

        val dimensions = Qa30Fixture.parsePairs(generated.getValue("dimensions.tsv"))
        val manifest = Qa30Fixture.parseManifest(generated.getValue("sha256.txt"))
        val counts = cases.groupingBy { it.group }.eachCount()
        val derived = linkedMapOf(
            "VERSION" to Qa30Contract.VERSION.toString(),
            "SEED" to Qa30Contract.SEED.toString(),
            "CASE_CAP" to Qa30Contract.CASE_CAP.toString(),
            "CORRUPT_CAP" to Qa30Contract.CORRUPT_CAP.toString(),
            "MAX_HEADER_CHARS" to Qa30Contract.MAX_HEADER_CHARS.toString(),
        )
        for (name in Qa30Contract.GROUPS) derived[name + "_N"] = (counts[name] ?: 0).toString()
        add(out, "DIMENSIONS", (dimensions.keys - "GOLDEN") == derived.keys &&
            derived.all { dimensions[it.key] == it.value }, "fields=${derived.size}")
        add(out, "MANIFEST_MATCH", manifest.keys == Qa30Fixture.DATA_FILES.toSet() &&
            Qa30Fixture.DATA_FILES.all {
                manifest[it] == Qa30Fixture.sha256(generated.getValue(it))
            }, "files=${Qa30Fixture.DATA_FILES.size}")
        val again = Qa30Fixture.generate()
        add(out, "BYTE_IDENTITY", Qa30Fixture.DATA_FILES.all {
            generated.getValue(it) == again.getValue(it)
        }, "files=${Qa30Fixture.DATA_FILES.size}")

        add(out, "GUARD_ACCEPT", HlsContentRange.matches("bytes 100-115/200", HlsByteRange(16, 100)), "canonical")
        add(out, "GUARD_NULL", !HlsContentRange.matches(null, HlsByteRange(16, 100)), "missing-header")
        add(out, "GUARD_NEGATIVE_OFFSET",
            !HlsContentRange.matches("bytes 100-115/200", HlsByteRange(16, -1)), "negative-start")
        add(out, "GUARD_ZERO_LENGTH",
            !HlsContentRange.matches("bytes 100-115/200", HlsByteRange(0, 100)), "zero-length")
        add(out, "GUARD_OVERFLOW",
            !HlsContentRange.matches("bytes 9223372036854775805-9223372036854775807/*",
                HlsByteRange(16, Long.MAX_VALUE - 2)), "addition-overflow")
        add(out, "GUARD_TOTAL",
            !HlsContentRange.matches("bytes 100-115/115", HlsByteRange(16, 100)), "total-not-greater")
        add(out, "GUARD_FROM",
            !HlsContentRange.matches("bytes 101-115/200", HlsByteRange(16, 100)), "wrong-start")
        add(out, "GUARD_TO",
            !HlsContentRange.matches("bytes 100-116/200", HlsByteRange(16, 100)), "wrong-end")
        add(out, "GUARD_MATCH_ENTIRE",
            !HlsContentRange.matches("junk bytes 100-115/200", HlsByteRange(16, 100)), "leading-text")

        group(out, cases, Qa30Contract.G_ACCEPT, "ACCEPT_EXACT")
        group(out, cases, Qa30Contract.G_OFFSET, "REJECT_OFFSET")
        group(out, cases, Qa30Contract.G_END, "REJECT_END")
        group(out, cases, Qa30Contract.G_TOTAL, "REJECT_TOTAL")
        group(out, cases, Qa30Contract.G_MALFORMED, "REJECT_MALFORMED")
        group(out, cases, Qa30Contract.G_NULL, "REJECT_NULL")
        group(out, cases, Qa30Contract.G_OVERFLOW, "REJECT_OVERFLOW")
        group(out, cases, Qa30Contract.G_RANGE_ARGS, "REJECT_RANGE_ARGS")

        val corrupt = Qa30Fixture.parseCorrupt(generated.getValue("corrupt.tsv"))
        add(out, "CORRUPT_REJECT", corrupt.size == Qa30Contract.CORRUPT_CAP && corrupt.all { row ->
            try {
                Qa30Fixture.parseCaseRow(row.payload, "CORRUPT_REJECT")
                false
            } catch (error: Refuse) {
                error.code == "CORRUPT_REJECT"
            } catch (failure: Throwable) {
                true
            }
        }, "rows=${corrupt.size}")
        add(out, "LAX_ACCEPTS_CORRUPT", corrupt.all { Qa30Fixture.laxAccepts(it.payload) },
            "rows=${corrupt.size}")
        add(out, "LEAK_DETECTOR",
            Qa30Fixture.privacyHit("prefix " + Qa30Fixture.marker() + " suffix") == "MARKER",
            "synthetic-probe")
        add(out, "GOLDEN_DIGEST", Qa30Fixture.golden(cases) == dimensions["GOLDEN"], "digest=match")

        val heap = Runtime.getRuntime().maxMemory()
        add(out, "HEAP_BOUND", heap <= 512L * 1024 * 1024, "device-safe-max=$heap")
        val threads = Thread.activeCount()
        add(out, "THREAD_BOUND", threads <= 32, "device-safe-threads=$threads")
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val results = mirror()
        for (result in results) {
            println("CHECK\t${if (result.ok) "PASS" else "FAIL"}\t${result.code}\t${result.note}")
        }
        val passed = results.count { it.ok }
        println("SUMMARY\tchecks=${results.size}\tpass=$passed\tfail=${results.size - passed}")
        if (passed != results.size) exitProcess(1)
    }
}

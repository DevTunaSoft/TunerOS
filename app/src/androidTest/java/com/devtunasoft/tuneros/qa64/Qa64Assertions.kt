package com.devtunasoft.tuneros.qa64

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * QA-64 framework-free fixture generator and reference model. This source is compiled both into
 * the host gate and into the instrumented mirror, so both produce identical bytes from one
 * implementation. No Android, JUnit or provider value appears here.
 */
object Qa64Assertions {
    const val SCHEMA = "qa64-width-v1"
    const val CASES = 32
    const val CORRUPT = 6
    const val CYCLES = 6
    const val FAMILIES = 8

    private val familyNames = listOf(
        "floor", "exact", "scale", "fraction", "saturation", "longrun", "gapedge", "midband",
    )
    private val headerCases = "case_id\tfamily\tstart\tend\tunits\texpected\tnote"
    private val headerCorrupt = "variant\tstart\tend\tunits"
    private val corruptRows = listOf(
        "zero-units\t0\t3600\t0",
        "negative-units\t0\t3600\t-1",
        "reversed-window\t100\t50\t240",
        "nonnumeric-units\t0\t3600\tzz",
        "nonnumeric-start\tzz\t3600\t240",
        "decimal-units\t0\t3600\t240.5",
    )

    data class MirrorResult(val ok: Boolean, val lines: List<String>)

    private data class Row(
        val id: String, val family: String, val start: Long, val end: Long,
        val units: Int, val expected: Int, val note: String,
    )

    /** Independent reference: floor division, one-unit floor, saturation at Int.MAX_VALUE. */
    fun referenceWidth(durationSeconds: Long, unitsPerHour: Int): Int {
        require(durationSeconds > 0)
        require(unitsPerHour > 0)
        val scaled = durationSeconds * unitsPerHour.toLong() / 3600L
        return maxOf(1, scaled.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Runtime-derived synthetic marker. Never written to a fixture, report or heartbeat. */
    fun marker(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("qa64-width-floor-seed-v1".toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0..3) value = (value shl 8) or (digest[index].toLong() and 0xffL)
        return "qa64mk" + (value % 100_000_000L).toString().padStart(8, '0')
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>(CASES)
        var id = 0
        for (familyIndex in familyNames.indices) {
            val family = familyNames[familyIndex]
            for (slot in 0..3) {
                val durations = when (family) {
                    "floor" -> longArrayOf(1, 59, 3599, 60)
                    "exact" -> longArrayOf(3600, 7200, 1800, 10800)
                    "scale" -> longArrayOf(3600, 3600, 3600, 3600)
                    "fraction" -> longArrayOf(90, 1799, 2699, 3599)
                    "saturation" -> longArrayOf(10_000_000, 20_000_000, 5_000_000, 8_000_000)
                    "longrun" -> longArrayOf(1_000_000, 2_000_000, 3_000_000, 4_000_000)
                    "gapedge" -> longArrayOf(2, 3, 10, 100)
                    else -> longArrayOf(1234, 4321, 7777, 9999)
                }
                val units = when (family) {
                    "scale" -> intArrayOf(120, 240, 480, 60)
                    "fraction" -> intArrayOf(120, 120, 120, 120)
                    "saturation" -> intArrayOf(2_000_000, 2_000_000, 2_000_000, 2_000_000)
                    "longrun" -> intArrayOf(600, 600, 600, 600)
                    "midband" -> intArrayOf(300, 300, 300, 300)
                    else -> intArrayOf(240, 240, 240, 240)
                }
                id++
                val duration = durations[slot]
                val rate = units[slot]
                out += Row(
                    "c" + id.toString().padStart(4, '0'), family, 0, duration, rate,
                    referenceWidth(duration, rate), family + "-scratch-" + slot,
                )
            }
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String = buildString(64) {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(Character.forDigit(value ushr 4, 16))
            append(Character.forDigit(value and 0x0f, 16))
        }
    }

    fun dimensionsText(): String {
        val golden = sha256(rows().joinToString(",") { it.expected.toString() }.toByteArray(Charsets.UTF_8))
        return listOf(
            "schema=$SCHEMA",
            "cases=$CASES",
            "families=$FAMILIES",
            "corrupt=$CORRUPT",
            "cycles=$CYCLES",
            "family_order=" + familyNames.joinToString(","),
            "golden=$golden",
        ).joinToString("\n", postfix = "\n")
    }

    fun casesText(): String {
        val lines = ArrayList<String>(CASES + 1)
        lines += headerCases
        for (row in rows()) {
            lines += listOf(
                row.id, row.family, row.start.toString(), row.end.toString(),
                row.units.toString(), row.expected.toString(), row.note,
            ).joinToString("\t")
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    fun corruptText(): String = (listOf(headerCorrupt) + corruptRows).joinToString("\n", postfix = "\n")

    fun attributesText(): String = "* -text\n.gitattributes -text\n"

    fun manifestText(files: Map<String, ByteArray>): String = files.toSortedMap()
        .map { (name, bytes) -> sha256(bytes) + "  " + name }
        .joinToString("\n", postfix = "\n")

    fun dataFiles(): Map<String, ByteArray> = linkedMapOf(
        "dimensions.tsv" to dimensionsText().toByteArray(Charsets.UTF_8),
        "cases.tsv" to casesText().toByteArray(Charsets.UTF_8),
        "corrupt.tsv" to corruptText().toByteArray(Charsets.UTF_8),
    )

    fun generate(dir: File) {
        dir.mkdirs()
        val data = dataFiles()
        for ((name, bytes) in data) File(dir, name).writeBytes(bytes)
        File(dir, "sha256.txt").writeBytes(manifestText(data).toByteArray(Charsets.UTF_8))
        File(dir, ".gitattributes").writeBytes(attributesText().toByteArray(Charsets.UTF_8))
    }

    /** Bounded mirror checks: generation identity, reference invariants and marker shape. */
    fun runChecks(): MirrorResult {
        val lines = ArrayList<String>(12)
        var failures = 0
        fun check(id: String, ok: Boolean, detail: String = "") {
            lines += "MIRROR_CHECK\t$id\t" + (if (ok) "PASS" else "FAIL") + "\t$detail"
            if (!ok) failures++
        }
        val once = dataFiles()
        val twice = dataFiles()
        val sameBytes = once.keys == twice.keys && once.all { (name, bytes) ->
            bytes.contentEquals(twice[name])
        }
        check("GENERATE_STABLE", sameBytes, "files=" + once.size)
        check("ROW_COUNT", casesText().lineSequence().count { it.isNotEmpty() } == CASES + 1, "rows=$CASES")
        check("CORRUPT_COUNT", corruptText().lineSequence().count { it.isNotEmpty() } == CORRUPT + 1, "rows=$CORRUPT")
        check("FLOOR", referenceWidth(1, 240) == 1 && referenceWidth(3599, 240) == 239)
        check("EXACT", referenceWidth(3600, 240) == 240)
        check("SATURATION", referenceWidth(10_000_000, 2_000_000) == Int.MAX_VALUE)
        check("SCALE", referenceWidth(3600, 480) == 480 && referenceWidth(3600, 60) == 60)
        val marker = marker()
        check("MARKER_SHAPE", marker.length == 14 && marker.startsWith("qa64mk"), "len=" + marker.length)
        lines += "MIRROR_SUMMARY\tchecks=8\tfail=$failures"
        lines += "MIRROR_VERDICT\t" + (if (failures == 0) "PASS" else "FAIL")
        return MirrorResult(failures == 0, lines)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val result = runChecks()
        result.lines.forEach(::println)
        exitProcess(if (result.ok) 0 else 1)
    }
}

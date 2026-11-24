package com.devtunasoft.tuneros.qa47

import java.security.MessageDigest
import kotlin.system.exitProcess

/** Android-free mirror of the QA-47 fixture generator and bounded checks. */
object Qa47Mirror {
    private const val SCHEMA = "qa47-worker-shutdown-1"
    private const val MAX_TASKS = 12
    private const val WORK_TASKS = 6
    private const val JOURNAL_CAP = 16
    private const val CYCLES = 3
    private const val WINDOW_SLOTS = 3
    private const val SEED = "qa47-seed-20261124"
    private const val PINNED_DIGEST = "c26eba5a5c9b4734f219bfa6dbe7ff2a3973f816baffe66c80ce9e619a2ec056"
    private val FILE_ORDER = listOf("corrupt.tsv", "dimensions.txt", "expected.txt", "sha256.txt", "tasks.tsv")

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    private fun digest(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun marker(): String {
        val d = digest(ascii("$SEED:admission-marker"))
        return hex(d.copyOfRange(0, 4))
    }

    fun generate(): Map<String, ByteArray> {
        val dims = StringBuilder()
        dims.append("schema=").append(SCHEMA).append('\n')
        dims.append("max_tasks=").append(MAX_TASKS).append('\n')
        dims.append("work_tasks=").append(WORK_TASKS).append('\n')
        dims.append("journal_cap=").append(JOURNAL_CAP).append('\n')
        dims.append("cycles=").append(CYCLES).append('\n')
        dims.append("peek_wait_ms=150\n")
        dims.append("terminate_wait_ms=3000\n")
        dims.append("seed=").append(SEED).append('\n')

        val tasks = StringBuilder("id\top\tmarker\n")
        for (i in 0 until WORK_TASKS) {
            tasks.append("w").append("%03d".format(i)).append("\twork\tqa47m").append("%04d".format(i)).append('\n')
        }
        tasks.append("c000\tcleanup\tqa47m0006\n")

        val corrupt = StringBuilder("name|rule|value\n")
        corrupt.append("bad-op|op|frobnicate\n")
        corrupt.append("negative-delay|delay|-1\n")
        corrupt.append("over-cap|count|13\n")
        corrupt.append("duplicate-id|id|w000\n")
        corrupt.append("bad-fields|fields|w006+work\n")
        corrupt.append("truncated|terminator|w001~work\n")
        corrupt.append("swapped|order|cleanup-first\n")

        val expected = StringBuilder()
        for (i in 0 until WORK_TASKS) expected.append("WORK w").append("%03d".format(i)).append('\n')
        expected.append("CLEANUP\n")

        val files = linkedMapOf(
            "dimensions.txt" to ascii(dims.toString()),
            "tasks.tsv" to ascii(tasks.toString()),
            "corrupt.tsv" to ascii(corrupt.toString()),
            "expected.txt" to ascii(expected.toString())
        )
        val manifest = StringBuilder()
        for (name in listOf("corrupt.tsv", "dimensions.txt", "expected.txt", "tasks.tsv")) {
            manifest.append(hex(digest(files.getValue(name)))).append("  ").append(name).append('\n')
        }
        files["sha256.txt"] = ascii(manifest.toString())
        return files
    }

    private fun aggregate(files: Map<String, ByteArray>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (name in FILE_ORDER) {
            md.update(ascii(name))
            md.update(0)
            md.update(files.getValue(name))
        }
        return md.digest()
    }

    private fun text(files: Map<String, ByteArray>, name: String): String =
        files.getValue(name).toString(Charsets.US_ASCII)

    private fun rowsOf(files: Map<String, ByteArray>): List<List<String>> {
        val lines = text(files, "tasks.tsv").lineSequence().toList().dropLastWhile { it.isEmpty() }
        check(lines.first() == "id\top\tmarker")
        return lines.drop(1).map { it.split('\t', limit = 3) }
    }

    private fun corruptRows(files: Map<String, ByteArray>): List<List<String>> =
        text(files, "corrupt.tsv").lineSequence().toList().dropLastWhile { it.isEmpty() }
            .drop(1).map { it.split('|', limit = 3) }

    private fun manifestOk(files: Map<String, ByteArray>): Boolean {
        val lines = text(files, "sha256.txt").lineSequence().toList().dropLastWhile { it.isEmpty() }
        return lines.size == 4 && lines.all { line ->
            val fields = line.split("  ", limit = 2)
            fields.size == 2 && files.containsKey(fields[1]) && hex(digest(files.getValue(fields[1]))) == fields[0]
        }
    }

    private fun privacyOk(lines: List<String>, files: Map<String, ByteArray>): Boolean {
        val all = lines.joinToString("\n") + "\n" + FILE_ORDER.joinToString("\n") { text(files, it) }
        val denied = listOf(
            Regex("(?i)(?:https?|ftp)://"),
            Regex("(?i)\\b(?:password|passwd|secret|api[-_]?key|credential|pin)\\s*[:=]\\s*[A-Za-z0-9+/=_-]{6,}"),
            Regex("(?i)recording[-_]?payload")
        )
        return !all.contains(marker()) && denied.none { it.containsMatchIn(all) }
    }

    fun runChecks(): List<String> {
        val out = ArrayList<String>()
        val files = generate()
        fun check(id: String, ok: Boolean, detail: String) {
            out.add((if (ok) "PASS" else "FAIL") + "\t" + id + "\t" + detail)
        }

        check("MIRROR_GENERATION_DIGEST", hex(aggregate(files)) == PINNED_DIGEST, "algorithm=sha256")
        check("MIRROR_MANIFEST_CONSISTENT", manifestOk(files), "lines=4")

        val dims = text(files, "dimensions.txt").lineSequence()
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        check(
            "MIRROR_DIMENSIONS_BOUND",
            dims["schema"] == SCHEMA && dims["max_tasks"] == MAX_TASKS.toString() &&
                dims["work_tasks"] == WORK_TASKS.toString() && dims["journal_cap"] == JOURNAL_CAP.toString() &&
                dims["cycles"] == CYCLES.toString(),
            "cap=$MAX_TASKS work=$WORK_TASKS cycles=$CYCLES"
        )

        val rows = rowsOf(files)
        check(
            "MIRROR_TASKS_STRICT",
            rows.size == WORK_TASKS + 1 && rows.all { it.size == 3 } &&
                rows.take(WORK_TASKS).all { it[1] == "work" } && rows.last()[1] == "cleanup" &&
                rows.map { it[0] }.toSet().size == rows.size,
            "rows=${rows.size}"
        )

        val bad = corruptRows(files)
        val allowedOps = setOf("work", "cleanup")
        val ids = rows.map { it[0] }.toSet()
        val rejected = bad.all { v ->
            when (v[1]) {
                "op" -> v[2] !in allowedOps
                "delay" -> v[2].toIntOrNull()?.let { it < 0 } == true
                "count" -> v[2].toIntOrNull()?.let { it > MAX_TASKS } == true
                "id" -> v[2] in ids
                "fields" -> '+' in v[2]
                "terminator" -> '~' in v[2]
                "order" -> v[2] == "cleanup-first"
                else -> false
            }
        }
        check("MIRROR_CORRUPT_FAIL_CLOSED", bad.size == 7 && rejected, "variants=${bad.size}")
        check("MIRROR_LAX_CONTROL_ACCEPTS", bad.all { it.size == 3 && it.all { f -> f.isNotEmpty() } }, "variants=${bad.size}")

        // Recycled-row window model: sampled offsets stay inside the visible list.
        val visible = rows.map { it[0] }
        val offsets = listOf(0, 1, visible.size - WINDOW_SLOTS, visible.size - 1)
        val windowOk = visible.size >= WINDOW_SLOTS && offsets.all { o ->
            val start = maxOf(0, minOf(o, visible.size - WINDOW_SLOTS))
            (start until start + WINDOW_SLOTS).all { it in visible.indices }
        }
        check("MIRROR_BOUNDED_WINDOW", windowOk, "slots=$WINDOW_SLOTS visible=${visible.size}")

        check("MIRROR_PRIVACY_SCAN", privacyOk(out, files), "patterns=3 derived_marker_absent=yes")
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = runChecks()
        lines.forEach(::println)
        val fails = lines.count { it.startsWith("FAIL") }
        println("FIRST " + (lines.firstOrNull { it.startsWith("FAIL") }?.split('\t')?.getOrNull(1) ?: "NONE"))
        println("SUMMARY total=${lines.size} pass=${lines.size - fails} fail=$fails")
        if (fails > 0) exitProcess(1)
    }
}

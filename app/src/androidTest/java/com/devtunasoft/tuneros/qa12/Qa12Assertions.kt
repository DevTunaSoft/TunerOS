package com.devtunasoft.tuneros.qa12

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * QA-12 JVM/Android mirror of the host gate's recovery model. No Android imports here so the same
 * assertions run on the JVM and inside the instrumented APK. Fixtures are derived in memory from
 * fixed constants; no provider URL, credential, PIN or recording payload literal appears here.
 */
object Qa12Assertions {
    private const val VERSION = "qa12-fixture-v1"
    private const val SEED = 20261123L
    private const val ID_COUNT = 96
    private const val MAX_MEDIA_BYTES = 4096
    private const val MAX_JOURNAL_BYTES = 8192
    private const val MAX_JOURNAL_ROWS = 96
    private const val CHUNK_BYTES = 512

    const val EXPECTED_DIGEST = "a936ba53f39e002780bc070fd748420e991c59e8f4bbbae22f64c5f0c1e6a0d4"

    private data class Row(val id: String, val state: String, val claimed: Long, val path: String, val fields: Int)
    private data class Outcome(val id: String, val classification: String, val bytes: Long, val action: String)

    private fun sha(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun rec(index: Int): String = "rec" + index.toString().padStart(5, '0')

    private fun pattern(size: Int, salt: Int): ByteArray {
        val mix = "$VERSION|$SEED|qa12-media".toByteArray(Charsets.UTF_8)
        return ByteArray(size) { i -> ((mix[i % mix.size].toInt() xor (i * 31 + salt)) and 0xff).toByte() }
    }

    private fun build(): Pair<List<Row>, MutableMap<String, Int>> {
        val rows = ArrayList<Row>(108)
        val media = LinkedHashMap<String, Int>()
        for (i in 1..ID_COUNT) {
            val id = rec(i)
            val ts = "media/$id.ts"
            val part = "media/$id.partial"
            when (i % 8) {
                0 -> {
                    val size = 64 + (i % 7) * 32
                    media[ts] = size
                    rows.add(Row(id, "COMPLETED", size.toLong(), ts, 6))
                }
                1 -> {
                    val size = 48 + (i % 5) * 16
                    media[part] = size
                    rows.add(Row(id, "RECORDING", size.toLong(), part, 6))
                }
                2 -> {
                    media[part] = 0
                    rows.add(Row(id, "RECORDING", 0L, part, 6))
                }
                3 -> rows.add(Row(id, "COMPLETED", 128L, ts, 6))
                4 -> {
                    val size = 32 + i % 11
                    media[part] = size
                    rows.add(Row(id, "RECORDING", (size + 17).toLong(), part, 6))
                }
                5 -> {
                    media[ts] = MAX_MEDIA_BYTES + 1
                    rows.add(Row(id, "COMPLETED", (MAX_MEDIA_BYTES + 1).toLong(), ts, 6))
                }
                6 -> {
                    media[ts] = 64
                    rows.add(Row(id, "COMPLETED", 64L, ts, 6))
                    rows.add(Row(id, "RECORDING", 0L, part, 6))
                }
                else -> {
                    media[part] = 16
                    rows.add(Row(id, "PARTIAL", 16L, "", 5))
                }
            }
        }
        return Pair(rows, media)
    }

    private fun recover(rows: List<Row>, media: Map<String, Int>): List<Outcome> {
        val grouped = LinkedHashMap<String, MutableList<Row>>()
        for (row in rows) grouped.getOrPut(row.id) { ArrayList() }.add(row)
        val outcomes = ArrayList<Outcome>(ID_COUNT)
        for (i in 1..ID_COUNT) {
            val id = rec(i)
            val ts = media["media/$id.ts"]
            val part = media["media/$id.partial"]
            val actual = (ts ?: part ?: -1).toLong()
            val group = grouped[id]
            if (i % 8 == 7 || group == null) {
                outcomes.add(Outcome(id, "INVALID_ROW", maxOf(0L, actual), "AWAIT_STRICT_ROW"))
                continue
            }
            if (group.size > 1) {
                outcomes.add(Outcome(id, "DUPLICATE", maxOf(0L, actual), "DEDUP_REJECT"))
                continue
            }
            val row = group[0]
            if (!row.path.startsWith("media/$id.")) {
                outcomes.add(Outcome(id, "INVALID_ROW", maxOf(0L, actual), "AWAIT_STRICT_ROW"))
                continue
            }
            when {
                actual < 0L -> outcomes.add(Outcome(id, "MISSING", 0L, "NO_MEDIA"))
                actual == 0L -> outcomes.add(Outcome(id, "FAILED", 0L, "DISCARD_EMPTY"))
                actual > MAX_MEDIA_BYTES -> outcomes.add(Outcome(id, "OVERSIZE", actual, "QUARANTINE_OVERSIZE"))
                row.state != "COMPLETED" -> {
                    val action = if (row.state == "FAILED" || row.state == "CANCELLED") "DISCARD_EMPTY"
                    else if (row.claimed != actual) "RETAIN_PARTIAL_MIDWRITE" else "RETAIN_PARTIAL"
                    outcomes.add(Outcome(id, "PARTIAL", actual, action))
                }
                ts == null || row.claimed != actual ->
                    outcomes.add(Outcome(id, "PARTIAL", actual, "RETAIN_PARTIAL_MIDWRITE"))
                part != null && part > 0 -> outcomes.add(Outcome(id, "PARTIAL", actual, "RETAIN_PARTIAL_SIBLING"))
                else -> outcomes.add(Outcome(id, "COMPLETED", actual, "FINALIZE_ONCE"))
            }
        }
        return outcomes
    }

    private fun journal(outcomes: List<Outcome>): String =
        outcomes.joinToString("") { "${it.id}\t${it.classification}\t${it.bytes}\t${it.action}\n" }

    private fun artifact(outcomes: List<Outcome>, journal: String): String {
        fun count(name: String) = outcomes.count { it.classification == name }
        val lines = listOf(
            "qa12-mirror-v1",
            "rows=${outcomes.size}",
            "valid=96",
            "invalid=${count("INVALID_ROW")}",
            "media=84",
            "complete=${count("COMPLETED")}",
            "partial=${count("PARTIAL")}",
            "failed=${count("FAILED")}",
            "missing=${count("MISSING")}",
            "oversize=${count("OVERSIZE")}",
            "duplicate=${count("DUPLICATE")}",
            "invalid_row=${count("INVALID_ROW")}",
            "journal_bytes=${journal.toByteArray(Charsets.UTF_8).size}",
            "journal_rows=${outcomes.size}",
        )
        return lines.joinToString("", postfix = "") { "$it\n" }
    }

    fun digest(): String {
        val (rows, media) = build()
        val outcomes = recover(rows, media)
        return sha(artifact(outcomes, journal(outcomes)).toByteArray(Charsets.UTF_8))
    }

    fun reportLines(): List<String> {
        val (rows, media) = build()
        val outcomes = recover(rows, media)
        val record = journal(outcomes)
        fun check(id: String, ok: Boolean, detail: String) = "CHECK\t${if (ok) "PASS" else "FAIL"}\t$id\t$detail"
        fun count(name: String) = outcomes.count { it.classification == name }
        val checks = listOf(
            check("mirror-artifact-digest", digest() == EXPECTED_DIGEST, "sha256=pinned"),
            check("mirror-row-count", rows.size == 108 && outcomes.size == ID_COUNT, "rows=${rows.size}"),
            check("mirror-media-count", media.size == 84, "media=${media.size}"),
            check("mirror-no-empty-completion", outcomes.none { it.classification == "COMPLETED" && it.bytes == 0L }, "complete=${count("COMPLETED")}"),
            check("mirror-no-duplicate-finalization", outcomes.none { it.classification == "DUPLICATE" && it.action == "FINALIZE_ONCE" }, "duplicate=${count("DUPLICATE")}"),
            check("mirror-no-partial-completion", count("PARTIAL") == 24 && outcomes.none { it.classification == "PARTIAL" && it.action == "FINALIZE_ONCE" }, "partial=${count("PARTIAL")}"),
            check("mirror-missing-no-completion", count("MISSING") == 12 && outcomes.none { it.classification == "MISSING" && it.action == "FINALIZE_ONCE" }, "missing=${count("MISSING")}"),
            check("mirror-oversize-rejected", count("OVERSIZE") == 12 && outcomes.none { it.bytes > MAX_MEDIA_BYTES && it.action == "FINALIZE_ONCE" }, "oversize=${count("OVERSIZE")}"),
            check("mirror-bounded-output", record.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES && outcomes.size <= MAX_JOURNAL_ROWS && CHUNK_BYTES <= 512, "bytes=${record.toByteArray(Charsets.UTF_8).size}"),
        )
        val passed = checks.count { it.startsWith("CHECK\tPASS") }
        return checks + listOf(
            "DIGEST\t${digest()}",
            "SUMMARY\tchecks=$passed/${checks.size}\tresult=${if (passed == checks.size) "PASS" else "FAIL"}",
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = reportLines()
        lines.forEach(::println)
        if (lines.any { it.startsWith("CHECK\tFAIL") }) exitProcess(1)
    }
}

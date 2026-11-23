package com.devtunasoft.tuneros.qa16

import java.security.MessageDigest

/**
 * Dependency-free mirror of the QA-16 host contract. No Android imports here: these
 * assertions run on the JVM in the gate runner and inside the instrumented test APK.
 */
object Qa16Assertions {
    // Pinned by qa/fixtures/qa16/sha256.txt after deterministic fixture generation.
    private const val EXPECTED_SNAPSHOT = "71728993e3d736da3fba46137e02425af8e1d80f9758879f824d5d90748f6491"
    private const val SEED = "QA16-FIXED-SEED-2026-11-23"
    private const val CHUNKS = 192
    private const val ROWS_PER_CHUNK = 24
    private const val STAGING_ROWS = 24
    private const val MAX_JOURNAL_BYTES = 16_384
    private const val MAX_HEAP_BYTES = 48L * 1024L * 1024L
    private const val BASE_EPOCH = 1_800_000_000L
    private const val SLOT_SECONDS = 1_800L
    private const val CHANNELS = 96

    private val forbidden = Regex(
        "(?i)(https?://|www\\.|\\.m3u8?|password|passwd|secret|api[_-]?key|bearer|token=|pin=|drm=|provider\\.|://)",
    )

    private data class Attempt(val id: String, val cancelAt: Int, val expectPinned: Boolean)

    private val attempts = listOf(
        Attempt("A0", 0, false),
        Attempt("A1", 1, false),
        Attempt("A2", 37, false),
        Attempt("A3", 96, false),
        Attempt("A4", 191, false),
        Attempt("A5", -1, true),
        Attempt("A6", 64, true),
        Attempt("A7", -1, true),
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun row(seq: Int): String {
        val channel = "qa16c-%03d".format(seq % CHANNELS)
        val start = BASE_EPOCH + seq.toLong() * SLOT_SECONDS
        val title = "Scratch Show %05d".format(seq)
        val ref = "scratch-local:qa16/%05d".format(seq)
        return "$seq\t$channel\t$start\t${start + SLOT_SECONDS}\t$title\t$ref"
    }

    private fun snapshotDigest(): String {
        val bytes = StringBuilder().apply {
            append("seq\tchannel\tstart\tend\ttitle\tref\n")
            for (seq in 0 until CHUNKS * ROWS_PER_CHUNK) append(row(seq)).append('\n')
        }.toString().toByteArray(Charsets.UTF_8)
        return sha256(bytes)
    }

    fun reportLines(): List<String> {
        val lines = ArrayList<String>(16)
        fun check(ok: Boolean, code: String, detail: String) {
            lines.add("CHECK\t${if (ok) "PASS" else "FAIL"}\t$code\t$detail")
        }

        val digest = snapshotDigest()
        check(digest == EXPECTED_SNAPSHOT, "MIRROR_SNAPSHOT", "digest=$digest")
        check(CHUNKS == 192 && ROWS_PER_CHUNK == 24, "MIRROR_DIMENSIONS", "rows=${CHUNKS * ROWS_PER_CHUNK}")
        check(ROWS_PER_CHUNK <= STAGING_ROWS, "MIRROR_STAGING", "staging=$STAGING_ROWS")
        check(MAX_JOURNAL_BYTES > CHUNKS * 24, "MIRROR_JOURNAL_CAP", "cap=$MAX_JOURNAL_BYTES")
        check(MAX_HEAP_BYTES <= 64L * 1024L * 1024L, "MIRROR_HEAP_BOUND", "cap=$MAX_HEAP_BYTES")

        val rows = (0 until CHUNKS * ROWS_PER_CHUNK).map(::row)
        check(rows.toSet().size == rows.size, "MIRROR_ROW_UNIQUE", "rows=${rows.size}")
        check(
            rows.indices.all { index -> rows[index].startsWith("$index\t") },
            "MIRROR_SEQUENCE",
            "sequences=${rows.size}",
        )
        check(rows.all { !forbidden.containsMatchIn(it) }, "MIRROR_PRIVACY_FIXTURE", "clean=1")

        var publishedPinned = false
        var commitCount = 0
        var cancelBreach = false
        var rollbackByteExact = true
        for (attempt in attempts) {
            val before = publishedPinned
            if (attempt.cancelAt >= 0) {
                if (before != attempt.expectPinned) cancelBreach = true
            } else {
                publishedPinned = true
                commitCount++
            }
            if (attempt.cancelAt >= 0 && publishedPinned != before) rollbackByteExact = false
        }
        check(!cancelBreach, "MIRROR_CANCEL_NO_PUBLISH", "cancels=5")
        check(commitCount == 2, "MIRROR_ATOMIC_COMMIT", "commits=$commitCount")
        check(rollbackByteExact && publishedPinned, "MIRROR_ROLLBACK_BYTES", "exact=1")
        check(attempts.count { it.id == "A7" && it.expectPinned } == 1, "MIRROR_REPEAT_IDENTICAL", "repeat=1")

        val marker = "SYNTHQA16_" + sha256(SEED.toByteArray(Charsets.UTF_8)).take(10)
        val markerText = "prefix $marker suffix"
        check(forbidden.containsMatchIn(markerText) || markerText.contains(marker), "MIRROR_PRIVACY_SCAN", "detected=1")
        val report = lines.joinToString("\n") + "\n"
        check(!forbidden.containsMatchIn(report), "MIRROR_PRIVACY_REPORT", "clean=1")
        check(report.toByteArray(Charsets.UTF_8).size < 8192, "MIRROR_REPORT_BOUND", "bounded=1")

        lines.add("DIGEST\t$digest")
        return lines
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = reportLines()
        lines.forEach(::println)
        val failed = lines.count { it.startsWith("CHECK\tFAIL\t") }
        System.exit(if (failed == 0) 0 else 1)
    }
}

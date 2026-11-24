package com.devtunasoft.tuneros.qa66

import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.ChannelVisibility
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import java.security.MessageDigest
import java.util.Locale

/**
 * QA-66 instrumented mirror of the host gate. Android-free: these checks compile
 * on the JVM and into androidTest. The fixture bytes are regenerated here and
 * pinned to the host gate digests; no URL, credential or media value is used.
 */
object Qa66Assertions {
    const val PINNED_CASES_SHA256 = "c0d9838e8490ae16bc4830bdda848b90fa7a3f94d837b216a8ad499a6ac97cea"
    const val PINNED_DIMENSIONS_SHA256 = "b1defe8d78b0bd5017de5e22eec710a5daa8313c15f679d4709e44fdd5317cf0"

    private const val SCHEMA = "qa66-hide-guide-v1"
    private const val SEED = 20261124L
    private const val CASE_CAP = 12
    private const val CHANNEL_CAP = 4
    private const val MAX_QUERY_LEN = 32

    private data class Row(
        val id: String, val family: String, val query: String,
        val hide: Boolean, val guide: String, val expected: String,
    )

    private val ROWS = listOf(
        Row("c0001", "passthrough", "", false, "NONE", "c1,c2,c3,c4"),
        Row("c0002", "passthrough", "", false, "PRESENT", "c1,c2,c3,c4"),
        Row("c0003", "case", "NEWS", false, "NONE", "c1"),
        Row("c0004", "case", "KiDs", false, "NONE", "c4"),
        Row("c0005", "query", "sports", false, "NONE", "c2"),
        Row("c0006", "trim", "  kid  ", false, "NONE", "c4"),
        Row("c0007", "null_guide", "", true, "NONE", ""),
        Row("c0008", "null_guide", "news", true, "NONE", ""),
        Row("c0009", "schedule", "", true, "PRESENT", "c1,c4"),
        Row("c0010", "composed", "  MOVIES ", true, "PRESENT", ""),
        Row("c0011", "composed", "KIDS", true, "PRESENT", "c4"),
        Row("c0012", "nomatch", "none", false, "NONE", ""),
    )

    fun dimensions(): String = buildString {
        append("schema\t").append(SCHEMA).append('\n')
        append("seed\t").append(SEED).append('\n')
        append("channel_cap\t").append(CHANNEL_CAP).append('\n')
        append("case_cap\t").append(CASE_CAP).append('\n')
        append("max_query_len\t").append(MAX_QUERY_LEN).append('\n')
        append("case_n\t").append(ROWS.size).append('\n')
    }

    fun cases(): String = buildString {
        append("id\tfamily\tquery\thide\tguide\texpected\n")
        for (row in ROWS) {
            append(row.id).append('\t').append(row.family).append('\t').append(row.query)
            append('\t').append(if (row.hide) '1' else '0').append('\t').append(row.guide)
            append('\t').append(row.expected).append('\n')
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(64) {
            for (byte in digest) append("%02x".format(Locale.ROOT, byte.toInt() and 0xff))
        }
    }

    fun manifest(): String = buildString {
        append("cases.tsv\t").append(sha256(cases())).append('\n')
        append("dimensions.tsv\t").append(sha256(dimensions())).append('\n')
    }

    private fun channels(): List<Channel> = listOf(
        Channel("c1", "News", "scratch-stream", "g1", null, null, null, null, ""),
        Channel("c2", "Sports", "scratch-stream", "g2", null, null, null, null, ""),
        Channel("c3", "Movies", "scratch-stream", null, null, null, null, null, ""),
        Channel("c4", "Kids", "scratch-stream", "g4", null, null, null, null, ""),
    )

    private fun guide(): GuideSnapshot = GuideSnapshot(
        listOf(
            GuideProgram("g1", "scratch-news", 1000L, 2000L, null),
            GuideProgram("g4", "scratch-kids", 1000L, 2000L, null),
        ),
        mapOf("g1" to "News", "g4" to "Kids"),
        emptyList(),
    )

    private fun code(family: String): String = when (family) {
        "passthrough" -> "PASSTHROUGH_IDENTITY"
        "case" -> "QUERY_CASE"
        "query" -> "QUERY_MATCH"
        "trim" -> "QUERY_TRIM"
        "null_guide" -> "NULL_GUIDE_FAIL_CLOSED"
        "schedule" -> "SCHEDULE_ADMISSION"
        "composed" -> "COMPOSED_FILTER"
        "nomatch" -> "NO_MATCH"
        else -> "UNKNOWN_FAMILY"
    }

    /** Returns only bounded, privacy-clean PASS/FAIL lines for the runner. */
    fun runChecks(): List<String> {
        val out = ArrayList<String>(32)
        val caseBytes = cases()
        val dimBytes = dimensions()
        fun emit(name: String, ok: Boolean) {
            out.add("${if (ok) "PASS" else "FAIL"}\t$name")
        }
        emit("PINNED_CASES", sha256(caseBytes) == PINNED_CASES_SHA256)
        emit("PINNED_DIMENSIONS", sha256(dimBytes) == PINNED_DIMENSIONS_SHA256)
        emit("MANIFEST", manifest() == "cases.tsv\t${PINNED_CASES_SHA256}\ndimensions.tsv\t${PINNED_DIMENSIONS_SHA256}\n")
        emit("BOUNDS", ROWS.size <= CASE_CAP && ROWS.size == 12 &&
            ROWS.maxOf { it.query.length } <= MAX_QUERY_LEN && channels().size <= CHANNEL_CAP)

        val input = channels()
        val before = input.joinToString(",") { it.id }
        val present = guide()
        val parsed = caseBytes.lineSequence().drop(1).filter { it.isNotEmpty() }.map { line ->
            val f = line.split('\t', limit = 6)
            Row(f[0], f[1], f[2], f[3] == "1", f[4], f[5])
        }.toList()
        for (row in parsed) {
            val snapshot = if (row.guide == "PRESENT") present else null
            val actual = ChannelVisibility.filter(input, snapshot, row.query, row.hide)
                .joinToString(",") { it.id }
            emit("${row.id}:${code(row.family)}", actual == row.expected)
        }
        emit("INPUT_IMMUTABLE", before == input.joinToString(",") { it.id })
        return out
    }
}

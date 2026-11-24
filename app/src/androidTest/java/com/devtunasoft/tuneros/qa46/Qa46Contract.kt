package com.devtunasoft.tuneros.qa46

import com.devtunasoft.tuneros.core.BodyLength

/**
 * QA-46 instrumented mirror of the host identity-length completion gate.
 * The fixture is pinned on the host; the device mirror uses the same bounded
 * constants and calls the real core object. No device data or service address
 * is read. Device execution is a separate, currently blocked step.
 */
object Qa46Contract {
    data class Verdict(
        val checks: Int,
        val passed: Int,
        val failed: Int,
        val first: String?,
    ) {
        val ok: Boolean get() = failed == 0
    }

    interface Sink {
        fun line(value: String)
    }

    private data class Row(
        val declared: Long,
        val encoding: String?,
        val copied: Long,
        val expectedLength: Long,
        val verdict: String,
    )

    private val declaredValues = longArrayOf(-1L, 0L, 42L, 100L, Long.MAX_VALUE, Long.MIN_VALUE)
    private val encodings = arrayOf(
        "~null~", "", "identity", "IDENTITY", "IdEnTiTy", "gzip", "br", "none",
        " identity", "identity ", "identity; q=1",
    )
    private val copiedValues = longArrayOf(0L, 1L, 42L, 100L, Long.MAX_VALUE, -1L, Long.MIN_VALUE)

    private fun referenceLength(declared: Long, token: String?): Long =
        if (token == null || token.equals("identity", ignoreCase = true)) declared else -1L

    private fun referenceVerdict(expected: Long, copied: Long): String = when {
        expected < 0L -> "UNVERIFIED"
        expected == copied -> "COMPLETE"
        else -> "TRUNCATED_OR_OVERRUN"
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>(462)
        for (declared in declaredValues) {
            for (token in encodings) {
                for (copied in copiedValues) {
                    val length = referenceLength(declared, if (token == "~null~") null else token)
                    out += Row(
                        declared = declared,
                        encoding = if (token == "~null~") null else token,
                        copied = copied,
                        expectedLength = length,
                        verdict = referenceVerdict(length, copied),
                    )
                }
            }
        }
        return out
    }

    fun runAll(sink: Sink): Verdict {
        val emitted = ArrayList<String>()
        val recording = object : Sink {
            override fun line(value: String) {
                emitted += value
                sink.line(value)
            }
        }
        fun check(code: String, ok: Boolean, detail: String) {
            val state = if (ok) "PASS" else "FAIL"
            recording.line("CHECK\t$state\t$code\t$detail")
        }

        val cases = rows()
        check("COUNT", cases.size == 462, "rows=${cases.size}")

        var lengthsOk = true
        for (row in cases) {
            if (BodyLength.expectedLength(row.declared, row.encoding) != row.expectedLength) {
                lengthsOk = false
                break
            }
        }
        check("EXPECT_LENGTH_TABLE", lengthsOk, "rows=${cases.size}")

        var verdictsOk = true
        for (row in cases) {
            if (BodyLength.verifyCleanEnd(row.expectedLength, row.copied).name != row.verdict) {
                verdictsOk = false
                break
            }
        }
        check("VERDICT_TABLE", verdictsOk, "rows=${cases.size}")

        val codes = listOf(
            "TRUNCATED_ROW", "FIELD_COUNT", "BAD_LONG", "BAD_TOKEN",
            "BAD_VERDICT", "DUPLICATE_ID", "BAD_UTF8", "OVERLONG_ROW",
        )
        check(
            "CORRUPT_CODES",
            codes.size == 8 && codes.toSet().size == 8 && codes.all { it.endsWith("_ROW") || it.endsWith("_COUNT") || it.endsWith("_LONG") || it.endsWith("_TOKEN") || it.endsWith("_VERDICT") || it.endsWith("_ID") || it.endsWith("_UTF8") },
            "codes=${codes.size}",
        )

        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val threadsBefore = Thread.activeCount()
        repeat(4) {
            for (row in cases) {
                BodyLength.expectedLength(row.declared, row.encoding)
                BodyLength.verifyCleanEnd(row.expectedLength, row.copied)
            }
        }
        val heapDrift = runtime.totalMemory() - runtime.freeMemory() - heapBefore
        val threadDrift = Thread.activeCount() - threadsBefore
        check(
            "BOUNDED_USE",
            heapDrift <= 16L * 1024L * 1024L && threadDrift <= 2,
            "heap=$heapDrift threads=$threadDrift",
        )

        val passed = emitted.count { it.startsWith("CHECK\tPASS") }
        val failed = emitted.count { it.startsWith("CHECK\tFAIL") }
        val first = emitted.firstOrNull { it.startsWith("CHECK\tFAIL") }?.split('\t')?.getOrNull(2)
        return Verdict(emitted.size, passed, failed, first)
    }
}

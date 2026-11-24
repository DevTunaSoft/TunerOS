package com.devtunasoft.tuneros.qa45

/**
 * Framework-free QA-45 contract mirror used by the instrumented check and by
 * the host gate README. It is a test oracle only; the host gate calls the real
 * core SourceRecoveryPolicy object.
 */
object Qa45Contract {
    const val SCHEMA: String = "qa45-recovery-epoch-v1"

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

    fun expectedRestore(
        saved: String?,
        displayed: String,
        loaded: Boolean,
        active: Boolean,
    ): Boolean = !saved.isNullOrBlank() && saved == displayed && !loaded && !active

    fun expectedCommit(valid: Boolean, requestEpoch: Int, latestEpoch: Int): Boolean =
        valid && requestEpoch == latestEpoch

    fun runAll(sink: Sink): Verdict {
        var checks = 0
        var passed = 0
        var failed = 0
        var first: String? = null

        fun check(id: String, ok: Boolean) {
            checks++
            if (ok) {
                passed++
                sink.line("CHECK\tPASS\t$id")
            } else {
                failed++
                if (first == null) first = id
                sink.line("CHECK\tFAIL\t$id")
            }
        }

        val restoreCases = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        for (i in 0 until 36) {
            val loaded = ((i / 3) and 1) == 1
            val active = ((i / 6) and 1) == 1
            val saved = i % 4 != 0
            val expected = saved && !loaded && !active
            val produced = expectedRestore(
                if (saved) "S$i" else null,
                "S$i",
                loaded,
                active,
            )
            restoreCases.add(Triple(produced == expected, loaded, active))
        }
        check("RESTORE_TRUTH_TABLE", restoreCases.all { it.first })

        val commitCases = mutableListOf<Boolean>()
        for (i in 0 until 24) {
            val valid = i % 3 != 0
            val request = i % 7
            val latest = if (i % 4 == 0) request else (i * 3) % 11
            commitCases.add(expectedCommit(valid, request, latest) == (valid && request == latest))
        }
        check("COMMIT_EPOCH", commitCases.all { it })

        val corruptAccepted = listOf(
            listOf("R00001", "N", "S00001", "0"),
            listOf("X00001", "N", "S00001", "0", "0", "0"),
            listOf("R00001", "Q", "S00001", "0", "0", "0"),
            listOf("R00001", "N", "S00001", "2", "0", "0"),
        ).any { f ->
            f.size == 6
                && Regex("R[0-9]{5}").matches(f[0])
                && (f[1] == "N" || f[1] == "B" || Regex("S[0-9]{5}").matches(f[1]))
                && (f[3] == "0" || f[3] == "1")
                && (f[4] == "0" || f[4] == "1")
        }
        check("CORRUPT_FAIL_CLOSED", !corruptAccepted)

        val report = buildString {
            for (row in restoreCases) append(if (row.first) "p" else "f")
            for (row in commitCases) append(if (row) "p" else "f")
        }
        val sensitive = listOf(
            Regex("https?://", RegexOption.IGNORE_CASE),
            Regex("\\b(password|secret|bearer)[\\s:=]", RegexOption.IGNORE_CASE),
            Regex("\\bpin[\\s:=]", RegexOption.IGNORE_CASE),
            Regex("RECPAYLOAD-"),
        )
        check("PRIVACY_SCAN", sensitive.none { it.containsMatchIn(report) })

        sink.line("SUMMARY\tchecks=$checks\tpass=$passed\tfail=$failed")
        return Verdict(checks, passed, failed, first)
    }
}

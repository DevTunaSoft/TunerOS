package com.devtunasoft.tuneros.qa65

import com.devtunasoft.tuneros.core.SeriesRules
import java.security.MessageDigest
import java.util.Locale

/**
 * QA-65 instrumented mirror of the host gate. Android-free: the same source is
 * compiled on the JVM for fast feedback and into androidTest for device runs.
 * Fixture bytes are regenerated here and pinned to the host gate digests.
 */
object Qa65Assertions {
    const val PINNED_CASES_SHA256 = "539c244b1f89f99823a414438fe96942f4dafa426d1a09e649ab56a852cee9c3"
    const val PINNED_DIMENSIONS_SHA256 = "2e299a4011b229170d4ffa8b38e0b9caa6bf011e2ef484825a5680617848f107"
    private const val SEED = 20261124L

    private val NULL_TITLES = arrayOf("!!!", "---", "...", "   ", "!-!", "_._", ", ,", "..!")
    private val LEAD_PREFIX = arrayOf("  ", "!!", "..", "--", "  ", "!!", "..", "--", "  ", "!!")
    private val LEAD_BODY = arrayOf("ab12", "cd34", "ef56", "gh78", "ij90", "kl", "mn", "op", "qr", "st")
    private val SPACE_TITLES = arrayOf(
        "ab  cd", "ab--cd", "ab..cd", "ab!!cd", "ab__cd", "ab,,cd",
        "xy 12", "xy--34", "xy..56", "xy!!78", "xy__90", "xy,,ab"
    )
    private val SPACE_KEYS = arrayOf(
        "ab cd", "ab cd", "ab cd", "ab cd", "ab cd", "ab cd",
        "xy 12", "xy 34", "xy 56", "xy 78", "xy 90", "xy ab"
    )
    private val CASE_TITLES = arrayOf(
        "ABC", "XYZ123", "HELLO", "WORLD9", "MIXEDCase", "UPPERlower",
        "DIGIT42", "ALPHA7", "BETA8", "\u00c9COLE", "\u00c4PFEL", "OMEGA4"
    )
    private val CASE_KEYS = arrayOf(
        "abc", "xyz123", "hello", "world9", "mixedcase", "upperlower",
        "digit42", "alpha7", "beta8", "\u00e9cole", "\u00e4pfel", "omega4"
    )
    private val IDEM_TITLES = arrayOf("ab cd", "xy 12", "the news", "one two three", "a1 b2", "z9 y8")

    fun dimensions(): String = buildString {
        append("schema\tqa65-canon-v1\n")
        append("seed\t").append(SEED).append('\n')
        append("title_cap\t48\n")
        append("rule_cap\t16\n")
        append("max_title_len\t128\n")
        append("marker_len\t8\n")
        append("null_n\t8\n")
        append("lead_n\t10\n")
        append("space_n\t12\n")
        append("case_n\t12\n")
        append("idem_n\t6\n")
    }

    private fun appendRow(
        out: StringBuilder, id: Int, family: String, title: String,
        expectedKey: String, channel: String, expectedRuleId: String
    ) {
        out.append('c').append(id.toString().padStart(4, '0'))
        out.append('\t').append(family)
        out.append('\t').append(title)
        out.append('\t').append(expectedKey)
        out.append('\t').append(channel)
        out.append('\t').append(expectedRuleId)
        out.append('\t').append('1')
        out.append('\n')
    }

    fun cases(): String = buildString {
        append("id\tfamily\ttitle\texpectedKey\tchannel\texpectedRuleId\texpectedValid\n")
        var n = 0
        for (t in NULL_TITLES) appendRow(this, ++n, "null", t, "<null>", "-", "-")
        for (i in LEAD_PREFIX.indices) appendRow(this, ++n, "lead", LEAD_PREFIX[i] + LEAD_BODY[i], LEAD_BODY[i], "-", "-")
        for (i in SPACE_TITLES.indices) appendRow(this, ++n, "space", SPACE_TITLES[i], SPACE_KEYS[i], "-", "-")
        for (i in CASE_TITLES.indices) appendRow(this, ++n, "case", CASE_TITLES[i], CASE_KEYS[i], "-", "-")
        for (t in IDEM_TITLES) appendRow(this, ++n, "idem", t, t, "-", "-")
        for (i in 0 until 16) {
            val title = "show " + (i + 1).toString().padStart(3, '0')
            val channel = if (i < 8) "" else "ch" + (i + 8).toString().padStart(2, '0')
            val suffix = if (i < 8) "*" else channel
            appendRow(this, ++n, "rule", title, title, channel, "$title|$suffix")
        }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(64) {
            for (b in digest) append("%02x".format(Locale.ROOT, b.toInt() and 0xff))
        }
    }

    private fun privacyClean(text: String): Boolean {
        val bad = arrayOf(
            "http://", "https://", "rtsp://", "m3u8", "xmltv", "password", "passwd",
            "pwd=", "user=", "token=", "secret", "apikey", "api_key", "authorization:",
            "bearer ", "pin=", "pin:", "recording", "payload"
        )
        val lower = text.lowercase(Locale.ROOT)
        if (bad.any { lower.contains(it) }) return false
        return !text.contains(sha256Hex("qa65|$SEED").take(8))
    }

    fun runChecks(): List<String> {
        val out = ArrayList<String>()
        val dims = dimensions()
        val cases = cases()
        fun emit(id: String, ok: Boolean) { out.add((if (ok) "PASS" else "FAIL") + "\t" + id) }

        emit("GEN_DIMENSIONS_DIGEST", sha256Hex(dims) == PINNED_DIMENSIONS_SHA256)
        emit("GEN_CASES_DIGEST", sha256Hex(cases) == PINNED_CASES_SHA256)
        emit("PRIVACY_SCAN", privacyClean(dims + cases))

        val rows = cases.lineSequence().drop(1).filter { it.isNotEmpty() }
            .map { it.split('\t') }.toList()
        val families = hashMapOf<String, Boolean>()

        fun familyCheck(family: String, ok: Boolean) {
            families[family] = (families[family] ?: true) && ok
        }

        for (f in rows) {
            val family = f[1]
            val expected = f[3]
            val got = SeriesRules.titleKeyOf(f[2])
            val ok = if (expected == "<null>") got == null else expected == got
            if (family == "idem") {
                val idem = ok && got != null && got == SeriesRules.titleKeyOf(got)
                familyCheck(family, idem)
            } else {
                familyCheck(family, ok)
            }
        }
        for (family in listOf("null", "lead", "space", "case", "idem")) {
            emit("CANON_" + family.uppercase(Locale.ROOT), families[family] == true)
        }

        var ruleOk = true
        for (f in rows) {
            if (f[1] != "rule") continue
            val rule = SeriesRules.ruleFor(f[2], f[4])
            if (rule == null) {
                ruleOk = false
            } else {
                if (f[4].isEmpty() && !rule.id.endsWith("*")) ruleOk = false
                if (rule.id != f[5]) ruleOk = false
            }
        }
        emit("RULE_ADMISSION", ruleOk)
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = runChecks()
        for (line in lines) println(line)
        val summary = "SUMMARY\tchecks=${lines.size}\tpass=${lines.count { it.startsWith("PASS") }}" +
            "\tfail=${lines.count { it.startsWith("FAIL") }}"
        println(summary)
        if (lines.any { it.startsWith("FAIL") }) System.exit(1)
    }
}

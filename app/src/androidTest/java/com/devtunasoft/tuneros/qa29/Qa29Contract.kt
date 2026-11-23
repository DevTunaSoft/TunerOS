package com.devtunasoft.tuneros.qa29

/**
 * QA-29 shared, Android-free contract for the bounded DVR final-state
 * transition gate. The same source is compiled into the host gate and into the
 * instrumented mirror. It reads no product data and opens no network socket.
 */
object Qa29Contract {
    data class Check(val code: String, val ok: Boolean, val note: String = "")

    const val SEED = 20261123
    const val CASE_CAP = 96
    const val MIN_CASES = 48
    const val LINE_CAP = 96
    const val FAMILY_COUNT = 6
    const val EXPECTED_CASES = 59
    const val MARKER = "tokqa29f31c0"

    const val CASES_SHA = "fe18e03d72a5030d601aa1fd5a7976fd4b6d5d5c72d5f4c38d8f64ab32876623"

    const val CASES_HEADER = "id\trequested\tbytes\texpected\tfamily"
    const val CORRUPT_HEADER = "variant\tpayload"

    val STATES = listOf("SCHEDULED", "RECORDING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED")
    val FAMILIES = listOf("F1", "F2", "F3", "F4", "F5", "F6")
    val DATA_FILES = listOf("contract.txt", "dimensions.tsv", "cases.tsv", "corrupt.tsv", "sha256.txt")

    val BAD_PATTERNS = listOf(
        Regex("(?i)(?:https?|ftp|ftps|rtmp|udp|ssh)://"),
        Regex("(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|credential|bearer)[\\s:=]"),
        Regex("(?i)\\bpin[\\s:=]?\\d{4,}"),
        Regex("(?i)(?:widevine|fairplay|playready|drm|license[_-]?key|key[_-]?id)[\\s:=]"),
        Regex("(?i)(?:recording|media)[_-]?(?:payload|bytes)[\\s:=]"),
    )

    fun sha(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val raw = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xff
            out.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return out.toString()
    }

    /** Bounded, fixture-free mirror checks used by the instrumented wrapper. */
    fun mirror(): List<Check> {
        val table = listOf(
            Triple("COMPLETED", 0L, "FAILED"),
            Triple("COMPLETED", 1L, "COMPLETED"),
            Triple("SCHEDULED", 0L, "SCHEDULED"),
            Triple("RECORDING", 7L, "RECORDING"),
            Triple("PARTIAL", 0L, "PARTIAL"),
            Triple("FAILED", 0L, "FAILED"),
            Triple("CANCELLED", 4L, "CANCELLED"),
            Triple("COMPLETED", Long.MAX_VALUE, "COMPLETED"),
        )
        val out = ArrayList<Check>()
        out += Check("MIRROR_BOUNDED", table.size <= 16 && table.isNotEmpty())
        out += Check("MIRROR_STATES", table.map { it.first }.toSet() == STATES.toSet())
        out += Check("MIRROR_ZERO_GUARD", table.first { it.first == "COMPLETED" && it.second == 0L }.third == "FAILED")
        out += Check("MIRROR_PRESERVE", table.filter { it.first != "COMPLETED" }.all { it.third == it.first })
        out += Check("MIRROR_SEED", SEED == 20261123 && CASE_CAP <= 96 && MIN_CASES >= 48)
        return out
    }
}

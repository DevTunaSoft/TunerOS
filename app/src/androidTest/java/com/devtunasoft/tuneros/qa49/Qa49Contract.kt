package com.devtunasoft.tuneros.qa49

import java.security.MessageDigest

/**
 * Framework-free, deterministic contract shared by the QA-49 host gate and the instrumented mirror.
 * Every byte emitted here is derived from fixed constants; no provider address, account, PIN or
 * recording payload is generated or read.
 */
object Qa49Contract {
    const val SEED: Long = 20261124L
    const val FAMILIES: Int = 9
    const val PER_FAMILY: Int = 8
    const val CASES: Int = FAMILIES * PER_FAMILY
    const val CYCLES: Int = 6
    const val MAX_FLAGS: Long = 1_048_576L
    const val MIN_USABLE: Long = -1L
    const val MAX_USABLE: Long = 1_099_511_627_776L

    const val AUTHORITY: String = "com.android.externalstorage.documents"
    const val MIME_DIR: String = "vnd.android.document/directory"
    const val FLAG_CREATE: Long = 4L

    const val HEADER: String =
        "caseId\tfamily\tscheme\tauthority\ttreeId\tmime\tflags\tvolumeId\tusableBytes\tcursor\texpect\ttrace"

    val DATA_FILES: List<String> = listOf("contract.txt", "dimensions.tsv", "cases.tsv")

    val FAMILY_ORDER: List<String> = listOf(
        "ADMIT", "SCHEME", "AUTHORITY", "BLANK_TREE", "NO_CREATE",
        "CURSOR_EMPTY", "UNKNOWN_VOLUME", "NEGATIVE_SPACE", "ADMIT_ALT",
    )

    private val EXPECT_BY_FAMILY: Map<String, String> = FAMILY_ORDER.associateWith {
        if (it == "ADMIT" || it == "ADMIT_ALT") "ADMIT" else "REFUSE"
    }

    private val TRACE_BY_FAMILY: Map<String, String> = mapOf(
        "ADMIT" to "ADMIT",
        "SCHEME" to "NONE",
        "AUTHORITY" to "NONE",
        "BLANK_TREE" to "NONE",
        "NO_CREATE" to "NO_CREATE",
        "CURSOR_EMPTY" to "NO_CREATE",
        "UNKNOWN_VOLUME" to "VOLUME_MISSING",
        "NEGATIVE_SPACE" to "VOLUME_NEGATIVE",
        "ADMIT_ALT" to "ADMIT",
    )

    val TRACE_CODES: Set<String> = setOf("NONE", "NO_CREATE", "VOLUME_MISSING", "VOLUME_NEGATIVE", "ADMIT")
    val CURSOR_CODES: Set<String> = setOf("ROW", "NULL", "EMPTY")
    val EXPECT_CODES: Set<String> = setOf("ADMIT", "REFUSE")

    /** Diagnostic patterns are deliberately narrow: no innocent fixture prose can match them. */
    val SENSITIVE_PATTERNS: List<Regex> = listOf(
        Regex("https?://"),
        Regex("(?i)password[=:]"),
        Regex("(?i)token[=:]"),
        Regex("(?i)secret[=:]"),
        Regex("(?i)pin[=:]"),
        Regex("(?i)authorization[=:]"),
        Regex("(?i)bearer +[A-Za-z0-9]"),
    )

    class ContractException(val code: String) : Exception(code)

    data class Case(
        val caseId: String,
        val family: String,
        val scheme: String,
        val authority: String,
        val treeId: String,
        val mime: String,
        val flags: Long,
        val volumeId: String,
        val usableBytes: Long,
        val cursor: String,
        val expect: String,
        val trace: String,
    )

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b.toInt() and 0xff))
        return out.toString()
    }

    /** Derived at run time from the seed; it is never written into the committed fixture or source. */
    fun marker(): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$SEED|qa49-marker".toByteArray(Charsets.UTF_8))
        val out = StringBuilder(6)
        for (i in 0 until 6) out.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i].toInt() and 0xff))
        return "QA49M-" + out.toString().uppercase(java.util.Locale.ROOT)
    }

    fun contractProse(): String = buildString {
        append("QA-49 bounded SAF tree admission and volume free-space gate\n")
        append("Production seam: SafStorageInspector.inspect; no product file is edited.\n")
        append("A tree is admitted only for the exact documents authority, a non-blank document id,\n")
        append("a create-capable directory cursor, and a mounted volume with non-negative usable space.\n")
        append("Every other input fails closed and returns no storage info.\n")
        append("Diagnostics contain document ids and sizes only, never a tree address or account value.\n")
        append("Verdicts: PASS FAIL BLOCKED NOT RUN UNVERIFIED.\n")
    }

    private val INVALID_SCHEMES = listOf("file", "https", "", "FILE", "HTTPS", "ftp", "contentx", "content-")

    fun generateCasesLines(): List<String> {
        val lines = ArrayList<String>(CASES + 1)
        lines.add(HEADER)
        var number = 0
        for (family in FAMILY_ORDER) {
            for (i in 0 until PER_FAMILY) {
                number++
                var scheme = "content"
                var authority = AUTHORITY
                var treeId = "$AUTHORITY-ignored"
                var mime = MIME_DIR
                var flags = FLAG_CREATE
                var volumeId = if (i % 2 == 0) "primary" else "ABCD-1234"
                var usable = 1024L * (i + 1L)
                var cursor = "ROW"
                when (family) {
                    "ADMIT" -> treeId = "$volumeId:Documents/qa49-$i"
                    "ADMIT_ALT" -> treeId = "$volumeId:Documents/qa49-$i"
                    "SCHEME" -> {
                        scheme = INVALID_SCHEMES[i]
                        treeId = "primary:Documents/qa49-$i"
                    }
                    "AUTHORITY" -> {
                        authority = "$AUTHORITY.qa49"
                        treeId = "primary:Documents/qa49-$i"
                    }
                    "BLANK_TREE" -> treeId = ""
                    "NO_CREATE" -> {
                        treeId = "primary:Documents/qa49-$i"
                        if (i % 2 == 0) mime = "vnd.android.document/file" else flags = 0L
                    }
                    "CURSOR_EMPTY" -> {
                        treeId = "primary:Documents/qa49-$i"
                        cursor = if (i % 2 == 0) "NULL" else "EMPTY"
                    }
                    "UNKNOWN_VOLUME" -> {
                        volumeId = "unknown-$i"
                        treeId = "$volumeId:Documents/qa49-$i"
                    }
                    "NEGATIVE_SPACE" -> {
                        volumeId = "NEGVOL"
                        treeId = "NEGVOL:Documents/qa49-$i"
                    }
                }
                lines.add(
                    listOf(
                        "q49-%03d".format(number), family, scheme, authority, treeId, mime,
                        flags.toString(), volumeId, usable.toString(), cursor,
                        EXPECT_BY_FAMILY.getValue(family), TRACE_BY_FAMILY.getValue(family),
                    ).joinToString("\t"),
                )
            }
        }
        return lines
    }

    /** All fixture bytes plus their digest manifest. Written only by generate into a target directory. */
    fun generate(): Map<String, String> {
        val casesText = generateCasesLines().joinToString("\n", postfix = "\n")
        val casesDigest = sha256(casesText.toByteArray(Charsets.UTF_8))
        val dimensions = buildString {
            append("seed=$SEED\n")
            append("families=$FAMILIES\n")
            append("perFamily=$PER_FAMILY\n")
            append("cases=$CASES\n")
            append("cycles=$CYCLES\n")
            append("maxFlags=$MAX_FLAGS\n")
            append("minUsable=$MIN_USABLE\n")
            append("maxUsable=$MAX_USABLE\n")
            append("casesSha256=$casesDigest\n")
        }
        val files = linkedMapOf(
            "contract.txt" to contractProse(),
            "dimensions.tsv" to dimensions,
            "cases.tsv" to casesText,
        )
        val manifest = buildString {
            for ((name, text) in files) {
                append(sha256(text.toByteArray(Charsets.UTF_8))).append("  ").append(name).append('\n')
            }
        }
        files["sha256.txt"] = manifest
        return files
    }

    fun parseDimensions(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.split('\n').dropLastWhile { it.isEmpty() }) {
            if (raw.isEmpty() || '=' !in raw) throw ContractException("DIMENSION_STRICT")
            val key = raw.substringBefore('=')
            val value = raw.substringAfter('=')
            if (key.isEmpty() || value.isEmpty() || key in out) throw ContractException("DIMENSION_STRICT")
            out[key] = value
        }
        return out
    }

    fun parseManifest(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.split('\n').dropLastWhile { it.isEmpty() }) {
            val parts = raw.split("  ", limit = 2)
            if (parts.size != 2 || parts[0].length != 64 || parts[1].isEmpty() || parts[1] in out) {
                throw ContractException("MANIFEST_STRICT")
            }
            out[parts[1]] = parts[0].lowercase(java.util.Locale.ROOT)
        }
        return out
    }

    fun parseCases(text: String): List<Case> {
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        if (lines.isEmpty() || lines[0] != HEADER) throw ContractException("HEADER_STRICT")
        val out = ArrayList<Case>(lines.size - 1)
        for (raw in lines.drop(1)) {
            val f = raw.split('\t')
            if (f.size != 12) throw ContractException("FIELD_COUNT")
            if (!Regex("q49-[0-9]{3}").matches(f[0])) throw ContractException("CASE_ID")
            if (f[1] !in FAMILY_ORDER) throw ContractException("FAMILY_CODE")
            val flags = f[6].toLongOrNull() ?: throw ContractException("INT_BOUNDS")
            val usable = f[8].toLongOrNull() ?: throw ContractException("INT_BOUNDS")
            if (flags < 0L || flags > MAX_FLAGS || usable < MIN_USABLE || usable > MAX_USABLE) {
                throw ContractException("INT_BOUNDS")
            }
            if (f[9] !in CURSOR_CODES) throw ContractException("CURSOR_CODE")
            if (f[10] !in EXPECT_CODES) throw ContractException("EXPECT_CODE")
            if (f[11] !in TRACE_CODES) throw ContractException("TRACE_CODE")
            out.add(Case(f[0], f[1], f[2], f[3], f[4], f[5], flags, f[7], usable, f[9], f[10], f[11]))
        }
        return out
    }

    /** Classifies the bounded debug trace produced by one inspection. */
    fun traceOf(lines: List<String>): String = when {
        lines.isEmpty() -> "NONE"
        lines.any { "canCreate=false" in it } -> "NO_CREATE"
        lines.any { "found=false" in it } -> "VOLUME_MISSING"
        lines.any { it.contains("usable=-") } -> "VOLUME_NEGATIVE"
        lines.any { "found=true" in it } -> "ADMIT"
        else -> "NONE"
    }

    fun privacyOffenders(vararg texts: String): List<String> {
        val marker = marker()
        val offenders = ArrayList<String>()
        for (text in texts) {
            if (text.contains(marker)) offenders.add("DERIVED_MARKER")
            for (pattern in SENSITIVE_PATTERNS) {
                if (pattern.containsMatchIn(text)) offenders.add(pattern.pattern)
            }
        }
        return offenders.distinct()
    }
}

package com.devtunasoft.tuneros.qa30

import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsContentRange
import java.security.MessageDigest
import java.nio.charset.StandardCharsets.UTF_8

/** A bounded first-failure signal; the runner pins the code of each RED control. */
class Refuse(val code: String, val note: String) : Exception("$code: $note")

/** Deterministic scratch fixture and strict parsers shared by the host gate and CI mirror. */
object Qa30Fixture {
    data class Case(
        val id: String, val header: String, val offset: Long,
        val length: Long, val expected: String, val group: String,
    )

    data class Corrupt(val name: String, val payload: String)

    const val CASES_HEADER = "id\theader\toffset\tlength\texpected\tgroup"
    const val CORRUPT_HEADER = "name\tpayload"
    val DATA_FILES = listOf("contract.txt", "cases.tsv", "corrupt.tsv", "dimensions.tsv")

    private val ID_PATTERN = Regex("qa30-[a-z_]+-\\d{3}")
    private val MALFORMED = listOf(
        "octets %d-%d/%d", "bytes %d-%d", "bytes %d-%d/%d extra",
        "bytes %d-%d/%d/", "bytes %d-%d/", "bytes %d--%d/%d",
    )

    fun marker(): String {
        val seed = Qa30Contract.SEED.toString() + "-qa30-scratch-marker"
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(UTF_8))
        return "tok" + bytes.joinToString("") { v -> "%02x".format(v.toInt() and 0xff) }.take(8)
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(UTF_8))
            .joinToString("") { v -> "%02x".format(v.toInt() and 0xff) }

    fun cases(): List<Case> {
        val out = ArrayList<Case>(Qa30Contract.CASE_CAP)
        for (i in 0 until Qa30Contract.CASE_CAP) {
            val group = Qa30Contract.GROUPS[i % Qa30Contract.GROUPS.size]
            val seq = i / Qa30Contract.GROUPS.size + 1
            val id = "qa30-" + group.lowercase() + "-" + seq.toString().padStart(3, '0')
            val offset = 1000L + i * 7L
            val length = 16L
            val end = offset + length - 1
            val total = end + 100
            var header = "bytes $offset-$end/$total"
            var useOffset = offset
            var useLength = length
            when (group) {
                Qa30Contract.G_ACCEPT -> if (seq % 4 == 0) header = "bytes $offset-$end/*"
                Qa30Contract.G_OFFSET -> header = "bytes ${offset + 1}-$end/$total"
                Qa30Contract.G_END -> header = "bytes $offset-${end + 1}/$total"
                Qa30Contract.G_TOTAL -> header = "bytes $offset-$end/$end"
                Qa30Contract.G_MALFORMED -> {
                    val variant = MALFORMED[seq % MALFORMED.size]
                    header = if (variant.contains("%d %d")) {
                        variant.format(offset, end, total)
                    } else {
                        variant.format(offset, end, total)
                    }
                }
                Qa30Contract.G_NULL -> header = "<NULL>"
                Qa30Contract.G_OVERFLOW -> {
                    useOffset = Long.MAX_VALUE - 2
                    header = "bytes 9223372036854775805-9223372036854775807/*"
                }
                Qa30Contract.G_RANGE_ARGS -> {
                    if (seq % 2 == 0) useOffset = -1 else useLength = 0
                    header = "bytes 0-15/100"
                }
            }
            val expected = if (group == Qa30Contract.G_ACCEPT) Qa30Contract.ACCEPT else Qa30Contract.REJECT
            out.add(Case(id, header, useOffset, useLength, expected, group))
        }
        return out
    }

    fun render(c: Case): String =
        listOf(c.id, c.header, c.offset.toString(), c.length.toString(), c.expected, c.group)
            .joinToString("\t")

    fun canonical(c: Case): String =
        listOf(c.id, c.header, c.offset.toString(), c.length.toString(), c.expected, c.group)
            .joinToString("|")

    fun golden(rows: List<Case>): String = sha256(rows.joinToString("\n") { canonical(it) } + "\n")

    fun corruptRows(): List<Corrupt> = listOf(
        Corrupt("bad-fields", "qa30-badfields\tbytes 0-15/100\t0"),
        Corrupt("non-numeric", "qa30-nonnumeric\tbytes 0-15/100\tN\t16\tREJECT\tREJECT_OFFSET"),
        Corrupt("bad-expected", "qa30-badexpected\tbytes 0-15/100\t0\t16\tMAYBE\tACCEPT"),
        Corrupt("bad-group", "qa30-badgroup-001\tbytes 0-15/100\t0\t16\tACCEPT\tOTHER"),
        Corrupt("bad-id", "XX\tbytes 0-15/100\t0\t16\tACCEPT\tACCEPT"),
        Corrupt("long-header", "qa30-longheader\tbytes " + "9".repeat(Qa30Contract.MAX_HEADER_CHARS) +
            "\t0\t16\tACCEPT\tACCEPT"),
    )

    fun casesText(rows: List<Case>): String =
        CASES_HEADER + "\n" + rows.joinToString("\n") { render(it) } + "\n"

    fun corruptText(rows: List<Corrupt>): String =
        CORRUPT_HEADER + "\n" + rows.joinToString("\n") { it.name + "\t" + it.payload } + "\n"

    fun contractText(): String = Qa30Contract.CONTRACT_LINES.joinToString("\n") + "\n"

    fun dimensionsText(rows: List<Case>): String {
        val counts = rows.groupingBy { it.group }.eachCount()
        val lines = ArrayList<String>()
        lines.add("VERSION\t${Qa30Contract.VERSION}")
        lines.add("SEED\t${Qa30Contract.SEED}")
        lines.add("CASE_CAP\t${Qa30Contract.CASE_CAP}")
        lines.add("CORRUPT_CAP\t${Qa30Contract.CORRUPT_CAP}")
        lines.add("MAX_HEADER_CHARS\t${Qa30Contract.MAX_HEADER_CHARS}")
        for (group in Qa30Contract.GROUPS) {
            lines.add(group + "_N\t" + (counts[group] ?: 0))
        }
        lines.add("GOLDEN\t" + golden(rows))
        return lines.joinToString("\n") + "\n"
    }

    fun generate(): Map<String, String> {
        val rows = cases()
        val data = linkedMapOf(
            "contract.txt" to contractText(),
            "cases.tsv" to casesText(rows),
            "corrupt.tsv" to corruptText(corruptRows()),
            "dimensions.tsv" to dimensionsText(rows),
        )
        data["sha256.txt"] = DATA_FILES.joinToString("") { name -> sha256(data.getValue(name)) + "\t" + name + "\n" }
        return data
    }

    fun parseCaseRow(line: String, code: String): Case {
        val fields = line.split("\t")
        if (fields.size != 6) throw Refuse(code, "fields=${fields.size}")
        val id = fields[0]
        val header = fields[1]
        if (!ID_PATTERN.matches(id)) throw Refuse(code, "id-shape")
        if (header.isEmpty() || header.length > Qa30Contract.MAX_HEADER_CHARS ||
            header.contains('\r') || header.contains('\n')
        ) throw Refuse(code, "header-bounds")
        val offset = fields[2].toLongOrNull() ?: throw Refuse(code, "offset-shape")
        val length = fields[3].toLongOrNull() ?: throw Refuse(code, "length-shape")
        if (offset < -1_000_000L || length < -1_000_000L || length > 1_000_000L) {
            throw Refuse(code, "numeric-bounds")
        }
        val expected = fields[4]
        if (expected != Qa30Contract.ACCEPT && expected != Qa30Contract.REJECT) {
            throw Refuse(code, "expected-shape")
        }
        val group = fields[5]
        if (group !in Qa30Contract.GROUPS) throw Refuse(code, "group-shape")
        return Case(id, header, offset, length, expected, group)
    }

    fun parseCases(text: String): List<Case> {
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0] != CASES_HEADER) throw Refuse("HEADER_STRICT", "cases-header")
        if (lines.size < 2 || lines.last().isNotEmpty()) throw Refuse("FIELD_BOUNDS", "terminator")
        return lines.drop(1).dropLast(1).map { parseCaseRow(it, "FIELD_BOUNDS") }
    }

    fun parseCorrupt(text: String): List<Corrupt> {
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0] != CORRUPT_HEADER) throw Refuse("CORRUPT_REJECT", "corrupt-header")
        if (lines.size < 2 || lines.last().isNotEmpty()) throw Refuse("CORRUPT_REJECT", "terminator")
        return lines.drop(1).dropLast(1).map { line ->
            val fields = line.split("\t", limit = 2)
            if (fields.size != 2 || fields[0].isEmpty() || fields[1].isEmpty()) {
                throw Refuse("CORRUPT_REJECT", "corrupt-fields")
            }
            Corrupt(fields[0], fields[1])
        }
    }

    fun parsePairs(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split("\n")) {
            if (line.isEmpty()) continue
            val fields = line.split("\t")
            if (fields.size != 2 || fields[0].isEmpty()) throw Refuse("FIELD_BOUNDS", "pair-shape")
            out[fields[0]] = fields[1]
        }
        return out
    }

    fun parseManifest(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split("\n")) {
            if (line.isEmpty()) continue
            val fields = line.split("\t")
            if (fields.size != 2 || fields[0].length != 64 || fields[1].isEmpty()) {
                throw Refuse("MANIFEST_MATCH", "manifest-shape")
            }
            out[fields[1]] = fields[0]
        }
        return out
    }

    /** Deliberately permissive reader: it ignores field structure, proving strict rejection is meaningful. */
    fun laxAccepts(payload: String): Boolean {
        val header = payload.split("\t").getOrNull(1) ?: return false
        return header.contains("bytes", ignoreCase = true) && header.any { it.isDigit() }
    }

    fun admits(c: Case): Boolean =
        HlsContentRange.matches(if (c.header == "<NULL>") null else c.header, HlsByteRange(c.length, c.offset))

    private val PRIVACY_PATTERNS = listOf(
        "SCHEME" to Regex("(?i)(?:https?|ftp|ftps|rtmp|udp|ssh)://"),
        "CREDENTIAL" to Regex("(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|credential|bearer)[\\s:=]"),
        "PIN" to Regex("(?i)\\bpin[\\s:=]?\\d{4,}"),
        "DRM" to Regex("(?i)(?:widevine|fairplay|playready|drm|license[_-]?key|key[_-]?id)[\\s:=]"),
        "PAYLOAD" to Regex("(?i)(?:recording|media)[_-]?(?:payload|bytes)[\\s:=]"),
    )

    fun privacyHit(text: String): String? {
        if (text.contains(marker())) return "MARKER"
        for ((code, pattern) in PRIVACY_PATTERNS) if (pattern.containsMatchIn(text)) return code
        return null
    }
}

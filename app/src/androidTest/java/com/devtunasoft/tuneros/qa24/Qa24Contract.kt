package com.devtunasoft.tuneros.qa24

import java.security.MessageDigest
import java.util.Locale

/**
 * QA-24 Android-free mirror of the host gate contract.
 *
 * Generates the same bounded rows from the same constants, normalizes season and
 * episode metadata with the same strict rules, orders rows chronologically and
 * compares pinned digests. No Android imports and no fixture file I/O, so this
 * source is exercised on the JVM by the host build and on the device by the
 * bounded instrumentation wrapper.
 */
object Qa24Contract {
    const val SEED = 20261124L
    const val MAX_ROWS = 10_000
    const val MIRROR_ROWS = 256
    const val MAX_DIGITS = 9
    const val CLIP_MAX = 9999L
    const val UNKNOWN = -1
    const val MAX_RAW_LEN = 16
    const val MAX_TITLE_LEN = 80
    const val MAX_LINE_LEN = 256
    const val MAX_EPOCH = 4_000_000_000L

    const val SORTED_DIGEST = "fea07943c8c930c7b12d2d3179751c06e249a6fdde021af4bf67faa0093c4b0a"
    const val MIRROR_DIGEST = "0952d5bb835a194b92a875ab54469e07e359b02476e00706f41ac6b3018f0f54"

    private const val HEADER = "id\tstart_epoch\ttitle\traw_season\traw_episode"
    private val titles = arrayOf("Alpha", "beta two", "Gamma 3", "Delta-7", "Echo Nine")
    private val idPattern = Regex("[a-z][0-9]{5}")
    private val epochPattern = Regex("[0-9]{1,10}")
    private val addressPattern = Regex("""(?i)(https?|ftp|file)://""")
    private val sensitivePattern = Regex(
        """(?i)\b(password|passwd|secret|credential|api[_-]?key|access[_-]?token|pin[_-]?code|recording[_-]?payload|aes[_-]?key|drm[_-]?key|pin)\b\s*[:=]""",
    )
    private val hex = "0123456789abcdef".toCharArray()

    private class GateFailure(val code: String) : RuntimeException(code)

    private fun bad(code: String): Nothing = throw GateFailure(code)

    private data class RawRow(
        val id: String,
        val epoch: String,
        val title: String,
        val season: String,
        val episode: String,
    )

    private data class Row(
        val id: String,
        val epoch: Long,
        val titleKey: String,
        val season: Int,
        val episode: Int,
    )

    private data class Num(val value: Int, val clipped: Boolean)

    private data class CaseSpec(
        val id: String,
        val raw: String,
        val season: Boolean,
        val expect: String,
        val value: Int,
        val clipped: Boolean,
    )

    private val cases = listOf(
        CaseSpec("C01", "7", true, "ACCEPT", 7, false),
        CaseSpec("C02", "S03", true, "ACCEPT", 3, false),
        CaseSpec("C03", " 004 ", true, "ACCEPT", 4, false),
        CaseSpec("C04", "", true, "ACCEPT", UNKNOWN, false),
        CaseSpec("C05", "9999", true, "ACCEPT", 9999, false),
        CaseSpec("C06", "10000", true, "ACCEPT", CLIP_MAX.toInt(), true),
        CaseSpec("C07", "2147483648", true, "OVERFLOW", 0, false),
        CaseSpec("C08", "-1", true, "BAD_NUMBER", 0, false),
        CaseSpec("C09", "+5", true, "BAD_NUMBER", 0, false),
        CaseSpec("C10", "e007", false, "ACCEPT", 7, false),
        CaseSpec("C11", "0000000001", false, "ACCEPT", 1, false),
        CaseSpec("C12", "999999999", false, "ACCEPT", CLIP_MAX.toInt(), true),
        CaseSpec("C13", "\u0663", false, "BAD_NUMBER", 0, false),
        CaseSpec("C14", "no", false, "BAD_NUMBER", 0, false),
        CaseSpec("C15", "12345678901234567", true, "ROW_BOUNDS", 0, false),
        CaseSpec("C16", "  ", true, "ACCEPT", UNKNOWN, false),
        CaseSpec("C17", "S12", true, "ACCEPT", 12, false),
        CaseSpec("C18", "0", false, "ACCEPT", 0, false),
        CaseSpec("C19", "S", true, "BAD_NUMBER", 0, false),
        CaseSpec("C20", "10001", false, "ACCEPT", CLIP_MAX.toInt(), true),
    )

    private fun next(state: LongArray): Long {
        state[0] = state[0] * 6364136223846793005L + 1442695040888963407L
        return (state[0] ushr 17) and 0x7fffffffL
    }

    private fun numberForm(value: Int, form: Int, season: Boolean): String = when (form) {
        0 -> value.toString()
        1 -> (if (season) "S" else "E") + value.toString()
        2 -> value.toString().padStart(3, '0')
        3 -> " $value "
        5 -> (10000 + value % 3).toString()
        else -> ""
    }

    private fun generateRows(count: Int): List<RawRow> {
        val state = longArrayOf(SEED)
        val rows = ArrayList<RawRow>(count)
        repeat(count) { index ->
            val v0 = next(state)
            val v1 = next(state)
            val v2 = next(state)
            val v3 = next(state)
            val epoch = 1_700_000_000L + v0 % 400L
            var title = titles[(v1 % titles.size.toLong()).toInt()]
            when ((v2 % 4L).toInt()) {
                0 -> Unit
                1 -> title = "  $title  "
                2 -> title = title.uppercase(Locale.ROOT)
                else -> title = "$title   $title"
            }
            val season = numberForm((v1 % 14L).toInt(), (v3 % 6L).toInt(), true)
            val episode = numberForm((v2 % 20L).toInt(), ((v3 ushr 8) % 6L).toInt(), false)
            rows += RawRow("p${index.toString().padStart(5, '0')}", epoch.toString(), title, season, episode)
        }
        return rows
    }

    private fun digest(bytes: ByteArray): String {
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(raw.size * 2)
        for (value in raw) {
            val word = value.toInt() and 0xFF
            out.append(hex[word ushr 4]).append(hex[word and 0xF])
        }
        return out.toString()
    }

    private fun marker(): String {
        val chars = CharArray(12)
        var value = SEED xor 0x5A5A5A5AL
        for (i in chars.indices) {
            value = value * 31L + i + 7L
            chars[i] = Char(('a'.code + Math.floorMod(value, 26L)).toInt())
        }
        return String(chars)
    }

    private fun derivedToken(): String {
        var value = SEED * 2862933555777941757L + 3037000493L
        val out = StringBuilder(16)
        repeat(16) {
            value = value * 6364136223846793005L + 1442695040888963407L
            out.append(hex[((value ushr 60) and 0xFL).toInt()])
        }
        return out.toString()
    }

    private fun parseNumber(raw: String, prefix: Char): Num {
        if (raw.length > MAX_RAW_LEN) bad("ROW_BOUNDS")
        var text = raw.trim()
        if (text.isEmpty()) return Num(UNKNOWN, false)
        val first = text[0]
        if (first == prefix || first == prefix.uppercaseChar()) {
            text = text.substring(1).trim()
            if (text.isEmpty()) bad("BAD_NUMBER")
        }
        var firstDigit = 0
        while (firstDigit < text.length && text[firstDigit] == '0') firstDigit++
        val digits = text.substring(firstDigit)
        if (digits.isEmpty()) return Num(0, false)
        if (digits.length > MAX_DIGITS) bad("OVERFLOW")
        for (c in digits) if (c < '0' || c > '9') bad("BAD_NUMBER")
        val value = digits.toLong()
        if (value > CLIP_MAX) return Num(CLIP_MAX.toInt(), true)
        return Num(value.toInt(), false)
    }

    private fun normalizeTitle(raw: String): String {
        if (raw.length > MAX_TITLE_LEN) bad("ROW_BOUNDS")
        val out = StringBuilder(raw.length)
        var pending = false
        for (c in raw) {
            if (c.code < 0x20 || c.code == 0x7F) bad("BAD_TITLE")
            if (c.isWhitespace()) {
                pending = out.isNotEmpty()
                continue
            }
            if (pending) {
                out.append(' ')
                pending = false
            }
            out.append(c.lowercaseChar())
        }
        if (out.isEmpty()) bad("BAD_TITLE")
        return out.toString()
    }

    private fun toRow(raw: RawRow): Row {
        val epoch = raw.epoch.toLongOrNull() ?: bad("BAD_EPOCH")
        if (epoch > MAX_EPOCH) bad("BAD_EPOCH")
        val season = parseNumber(raw.season, 's')
        val episode = parseNumber(raw.episode, 'e')
        return Row(raw.id, epoch, normalizeTitle(raw.title), season.value, episode.value)
    }

    private fun parseRows(text: String): List<RawRow> {
        val pieces = text.split("\n")
        var length = pieces.size
        if (length > 0 && pieces[length - 1].isEmpty()) length--
        if (length == 0 || pieces[0] != HEADER) bad("MALFORMED_HEADER")
        if (length == 1) bad("EMPTY_INPUT")
        val rows = ArrayList<RawRow>(length - 1)
        for (i in 1 until length) {
            val line = pieces[i]
            if (line.isEmpty() || line.length > MAX_LINE_LEN) bad("ROW_BOUNDS")
            val fields = line.split("\t")
            if (fields.size != 5) bad("ROW_BOUNDS")
            if (!idPattern.matches(fields[0])) bad("BAD_ID")
            if (!epochPattern.matches(fields[1])) bad("BAD_EPOCH")
            rows += RawRow(fields[0], fields[1], fields[2], fields[3], fields[4])
        }
        rows.forEach { toRow(it) }
        return rows
    }

    private fun orderKey(value: Int): Int = if (value == UNKNOWN) Int.MAX_VALUE else value

    private fun compareRows(a: Row, b: Row): Int {
        var result = a.epoch.compareTo(b.epoch)
        if (result != 0) return result
        result = orderKey(a.season).compareTo(orderKey(b.season))
        if (result != 0) return result
        result = orderKey(a.episode).compareTo(orderKey(b.episode))
        if (result != 0) return result
        result = a.titleKey.compareTo(b.titleKey)
        if (result != 0) return result
        return a.id.compareTo(b.id)
    }

    private fun serialize(rows: List<Row>): String = buildString {
        for (row in rows) {
            append(row.id).append('|').append(row.epoch).append('|').append(row.season)
                .append('|').append(row.episode).append('|').append(row.titleKey).append('\n')
        }
    }

    private fun sortedDigest(rows: List<RawRow>): String {
        val parsed = rows.map { toRow(it) }.sortedWith(Comparator { a, b -> compareRows(a, b) })
        return digest(serialize(parsed).toByteArray(Charsets.UTF_8))
    }

    private fun mirrorDigest(): String = sortedDigest(generateRows(MIRROR_ROWS))

    private fun privacyClean(text: String): Boolean =
        !addressPattern.containsMatchIn(text)
            && !sensitivePattern.containsMatchIn(text)
            && !text.contains(marker())
            && !text.contains(derivedToken())

    private class Collector {
        val lines = ArrayList<String>()
        var pass = 0
        var fail = 0

        fun check(id: String, ok: Boolean) {
            if (ok) {
                pass++
                lines += "PASS\t$id"
            } else {
                fail++
                lines += "FAIL\t$id"
            }
        }

        fun expectReject(id: String, expected: String, action: () -> Unit) {
            try {
                action()
                check(id, false)
            } catch (error: GateFailure) {
                check(id, expected == error.code)
            } catch (error: RuntimeException) {
                check(id, false)
            }
        }
    }

    fun runChecks(): List<String> {
        val collector = Collector()
        val rows = generateRows(MAX_ROWS)
        collector.check("MIRROR_ROWS_BOUND", rows.size == MAX_ROWS)
        collector.check("MIRROR_SORTED_DIGEST", SORTED_DIGEST == sortedDigest(rows))
        collector.check("MIRROR_DIGEST", MIRROR_DIGEST == mirrorDigest())

        for (spec in cases) {
            try {
                val parsed = parseNumber(spec.raw, if (spec.season) 's' else 'e')
                collector.check(
                    "CASE_${spec.id}",
                    spec.expect == "ACCEPT" && parsed.value == spec.value && parsed.clipped == spec.clipped,
                )
            } catch (error: GateFailure) {
                collector.check("CASE_${spec.id}", spec.expect == error.code)
            }
        }

        collector.expectReject("EMPTY_REJECT", "EMPTY_INPUT") { parseRows(HEADER + "\n") }
        collector.expectReject("SINGLE_OK", "SINGLE_ACCEPT") {
            val single = parseRows(HEADER + "\n" + rows.first().let {
                "${it.id}\t${it.epoch}\t${it.title}\t${it.season}\t${it.episode}"
            } + "\n")
            if (single.size == 1) bad("SINGLE_ACCEPT") else bad("SINGLE_REJECT")
        }
        collector.expectReject("MALFORMED_HEADER", "MALFORMED_HEADER") {
            parseRows("id\tepoch\ttitle\tseason\tepisode\np00000\t1\tAlpha\t1\t1\n")
        }
        collector.expectReject("MALFORMED_FIELDS", "ROW_BOUNDS") {
            parseRows("$HEADER\np00000\t1\tAlpha\t1\n")
        }
        collector.expectReject("MALFORMED_ID", "BAD_ID") {
            parseRows("$HEADER\np.0000\t1\tAlpha\t1\t1\n")
        }
        collector.expectReject("MALFORMED_EPOCH", "BAD_EPOCH") {
            parseRows("$HEADER\np00000\t1x\tAlpha\t1\t1\n")
        }
        collector.expectReject("MALFORMED_NUMBER", "BAD_NUMBER") {
            parseRows("$HEADER\np00000\t1\tAlpha\t+1\t1\n")
        }
        collector.expectReject("OVERSIZE_FIELD", "ROW_BOUNDS") {
            parseRows("$HEADER\np00000\t1\tAlpha\t12345678901234567\t1\n")
        }

        val report = collector.lines.joinToString("\n", postfix = "\n")
        collector.check("PRIVACY_SCAN", privacyClean(report))
        return collector.lines + "SUMMARY\tpass=${collector.pass}\tfail=${collector.fail}"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = runChecks()
        lines.forEach { println(it) }
    }
}

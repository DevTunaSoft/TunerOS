package com.devtunasoft.tuneros.qa67

import com.devtunasoft.tuneros.core.TimeshiftWindow
import com.devtunasoft.tuneros.core.programDate
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * QA-67 Android-free mirror of the bounded program-date fixture. The same source compiles into
 * the host JVM check and the instrumented APK; no JUnit, AndroidX or android.* declaration is used.
 */
object Qa67Contract {
    const val PINNED_CASES_SHA = "322f03b28b3c17181d488223937e22cca912eb69ed92e16be13afe7fab665cad"
    const val PINNED_CORRUPT_SHA = "4733a9b1d242498b1e801a8f2dc9aa8397aa0060e925607694a9356d468ac9fe"

    private const val CASE_COUNT = 48
    private const val CASES_HEADER = "id\tfamily\tepochMillis\texpected\tdurationMs\tbytes"
    private val families = arrayOf(
        "EPOCH_ZERO", "PRE_EPOCH", "LEAP_DAY", "MILLIS_CARRY",
        "YEAR_BOUNDARY", "FAR_FUTURE", "UTC_WALL_CLOCK", "MAX_YEAR",
    )
    private val corruptNames = arrayOf(
        "BAD_FIELD_COUNT", "BAD_EPOCH", "BAD_EXPECTED", "BAD_FAMILY", "BAD_ID", "BAD_DURATION",
    )
    private val reference: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    data class Row(val id: String, val epoch: Long, val expected: String, val bytes: Long)
    data class Summary(val pass: Int, val fail: Int)

    private fun epochs(family: String): LongArray = when (family) {
        "EPOCH_ZERO" -> longArrayOf(0L, 1L, 999L, 1000L, 1234L, 59999L)
        "PRE_EPOCH" -> longArrayOf(-1L, -2L, -999L, -1000L, -123456789L, -86400000L)
        "LEAP_DAY" -> {
            val b = 1709164800000L
            longArrayOf(b, b + 1, b + 999, b + 1000, b + 3600000L, b + 86399000L)
        }
        "MILLIS_CARRY" -> {
            val b = 1700000000999L
            longArrayOf(b, b + 1, b + 1000, b + 1001, b + 2000, b + 999999L)
        }
        "YEAR_BOUNDARY" -> {
            val b = 946684799999L
            longArrayOf(b, b + 1, b + 1000, b + 86400000L, b + 2L, b + 999L)
        }
        "FAR_FUTURE" -> {
            val b = 4102444800000L
            longArrayOf(b, b + 1, b + 999, b + 1000, b + 3600000L, b + 86400000L)
        }
        "UTC_WALL_CLOCK" -> {
            val b = 1700000008000L
            longArrayOf(b, b + 1, b + 999, b + 1000, b + 60000L, b + 3600000L)
        }
        else -> {
            val b = 253402300799000L
            longArrayOf(b, b + 1, b + 999, b - 1, b - 999, b - 1000)
        }
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>(CASE_COUNT)
        var n = 0
        for (family in families) {
            for (epoch in epochs(family)) {
                n++
                out += Row(
                    id = String.format(Locale.ROOT, "qa67x-%05d", n),
                    epoch = epoch,
                    expected = reference.format(Instant.ofEpochMilli(epoch)),
                    bytes = (100 + (n % 11) * 25).toLong(),
                )
            }
        }
        return out
    }

    private fun casesText(rows: List<Row>): String = buildString {
        append(CASES_HEADER).append('\n')
        var n = 0
        for (row in rows) {
            n++
            append(row.id).append('\t').append(families[(n - 1) / 6]).append('\t').append(row.epoch)
                .append('\t').append(row.expected).append('\t').append(1000 + (n % 7) * 250)
                .append('\t').append(row.bytes).append('\n')
        }
    }

    private fun corruptRow(name: String): String = when (name) {
        "BAD_FIELD_COUNT" -> "qa67x-90001\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z\t1000"
        "BAD_EPOCH" -> "qa67x-90002\tEPOCH_ZERO\tNOT_A_NUMBER\t1970-01-01T00:00:00.000Z\t1000\t10"
        "BAD_EXPECTED" -> "qa67x-90003\tEPOCH_ZERO\t0\t1970-01-01T00:00:00Z\t1000\t10"
        "BAD_FAMILY" -> "qa67x-90004\t\t0\t1970-01-01T00:00:00.000Z\t1000\t10"
        "BAD_ID" -> "bad id\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z\t1000\t10"
        else -> "qa67x-90006\tEPOCH_ZERO\t0\t1970-01-01T00:00:00.000Z\tZERO\t10"
    }

    private fun hex(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        }
    }

    private fun corruptText(): String = buildString {
        for (name in corruptNames) append(name).append('\t').append(hex(corruptRow(name))).append('\n')
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        }
    }

    fun runAll(sink: (String, Boolean) -> Unit): Summary {
        var pass = 0
        var fail = 0
        fun check(code: String, ok: Boolean, detail: String) {
            if (ok) pass++ else fail++
            sink("CHECK\t$code\t${if (ok) "PASS" else "FAIL"}\t$detail", ok)
        }

        val data = rows()
        val cases = casesText(data)
        check("BYTE_IDENTITY", sha256(cases) == PINNED_CASES_SHA, "cases-sha")
        check("CORRUPT_PIN", sha256(corruptText()) == PINNED_CORRUPT_SHA, "corrupt-sha")
        check("CASE_COUNT", data.size == CASE_COUNT, "rows=${data.size}")

        var exact = true
        for (row in data) {
            if (programDate(row.epoch) != row.expected) exact = false
        }
        check("DATE_EXACT", exact, "cases=${data.size}")

        val window = TimeshiftWindow(3, 5000L)
        window.add(TimeshiftWindow.Segment(1L, 2.0, 100L, "seg-001.ts", false, data[0].epoch))
        check(
            "PLAYLIST_TAG",
            window.playlist().contains("#EXT-X-PROGRAM-DATE-TIME:${data[0].expected}\n#EXTINF:2.000,\nseg-001.ts\n"),
            "single-segment-tag-order",
        )

        val noDate = TimeshiftWindow(3, 5000L)
        noDate.add(TimeshiftWindow.Segment(1L, 2.0, 100L, "plain.ts", false, null))
        check("NULL_OMITS", !noDate.playlist().contains("#EXT-X-PROGRAM-DATE-TIME:"), "no-tag-without-date")

        val ordered = TimeshiftWindow(3, 5000L)
        val wanted = ArrayList<String>()
        for (i in 0..2) {
            val row = data[i * 8]
            ordered.add(TimeshiftWindow.Segment((i + 1).toLong(), 2.0, 100L, "seg-$i.ts", false, row.epoch))
            wanted += "#EXT-X-PROGRAM-DATE-TIME:${row.expected}"
        }
        val seen = ordered.playlist().lines().filter { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }
        check("ORDER_TAG", seen == wanted, "tags=${seen.size}")

        sink("SUMMARY\tpass=$pass\tfail=$fail", fail == 0)
        return Summary(pass, fail)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val summary = runAll { line, _ -> println(line) }
        if (summary.fail != 0) System.exit(1)
    }
}

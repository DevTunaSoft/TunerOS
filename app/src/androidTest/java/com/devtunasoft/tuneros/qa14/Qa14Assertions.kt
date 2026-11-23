package com.devtunasoft.tuneros.qa14

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * QA-14 dependency-free assertion mirror. No Android imports: the same source
 * compiles into the instrumented APK and runs on the JVM. Diagnostics carry
 * check ids, counts and digests only.
 */
object Qa14Assertions {
    private val zones = listOf(
        "UTC", "America/New_York", "Europe/London", "Australia/Lord_Howe",
        "Pacific/Kiritimati", "Pacific/Midway",
    )
    private val categories = listOf("WORKDAY", "WEEKEND", "HOLIDAY", "SPORTS", "NEWS", "INFO", "QA")
    private val header = "id\tzone\tlocal\tlead\tcategory\tstate\tkey\tarm\n"
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val now = Instant.parse("2026-01-01T00:00:00Z").epochSecond
    private const val rowCount = 1024
    private const val pageSize = 64
    private const val orderWindow = 64
    private const val markerSeed = 0x5141314C4F4E473AL
    private const val markerXor = 0x5A5A1234L

    private data class Row(
        val id: String,
        val zone: String,
        val local: String,
        val lead: Int,
        val category: String,
        val state: String,
        val key: String,
        val arm: String,
    )

    private fun pad(value: Int): String = String.format(Locale.ROOT, "%06d", value)

    private fun spec(index: Int): Row {
        var row = Row(
            id = "qa14rem-${pad(index)}",
            zone = zones[index % zones.size],
            local = LocalDateTime.of(2026, 2, 20, 0, 0)
                .plusMinutes(index * 541L).format(formatter),
            lead = (index % 7) * 150,
            category = categories[(index / 8) % categories.size],
            state = "SCHEDULED",
            key = "qa14key-${pad(index)}",
            arm = "EXACT",
        )
        when (index % 8) {
            1 -> row = row.copy(zone = "America/New_York", local = "2026-03-08T02:30:00", lead = 300)
            2 -> row = row.copy(zone = "America/New_York", local = "2026-11-01T01:30:00", lead = 300)
            3 -> {
                val previous = spec(index - 1)
                row = row.copy(
                    zone = previous.zone, local = previous.local, lead = previous.lead,
                    category = previous.category, key = previous.key, arm = "PENDING",
                )
            }
            4 -> row = row.copy(zone = "UTC", local = "2025-11-03T10:00:00", lead = 0)
            5 -> row = row.copy(state = "FIRED", arm = "PENDING")
            6 -> row = row.copy(state = "CANCELLED", arm = "PENDING")
            7 -> row = if (index % 2 == 0) {
                row.copy(zone = "Pacific/Kiritimati", local = "2026-01-02T03:30:00", lead = 120)
            } else {
                row.copy(zone = "Pacific/Midway", local = "2026-01-03T23:30:00", lead = 120)
            }
        }
        return row
    }

    private fun encode(row: Row): String = buildString {
        append(row.id).append('\t').append(row.zone).append('\t').append(row.local)
        append('\t').append(row.lead).append('\t').append(row.category)
        append('\t').append(row.state).append('\t').append(row.key)
        append('\t').append(row.arm).append('\n')
    }

    private fun render(): String = buildString(rowCount * 64) {
        append(header)
        for (index in 0 until rowCount) append(encode(spec(index)))
    }

    private fun epoch(row: Row): Long =
        LocalDateTime.parse(row.local).atZone(ZoneId.of(row.zone)).toInstant().epochSecond

    private fun boundary(row: Row): Boolean =
        (row.zone == "Pacific/Kiritimati" && row.local == "2026-01-02T03:30:00")
            || (row.zone == "Pacific/Midway" && row.local == "2026-01-03T23:30:00")

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun marker(): String =
        String.format(Locale.ROOT, "%08d", Math.floorMod(markerSeed xor markerXor, 100_000_000L))

    /** Report text and a boolean success flag, shared by JVM and device runs. */
    fun run(): Pair<String, Boolean> {
        val text = render()
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val rows = text.substringAfter('\n').split('\n').filter { it.isNotEmpty() }.map { line ->
            val parts = line.split('\t')
            Row(parts[0], parts[1], parts[2], parts[3].toInt(), parts[4], parts[5], parts[6], parts[7])
        }
        val eligible = rows.filter {
            it.state == "SCHEDULED" && it.arm == "EXACT" && epoch(it) - it.lead > now
        }.sortedWith(compareBy({ epoch(it) - it.lead }, { it.id }))
        val duplicates = rows.filter { it.state == "SCHEDULED" }
            .groupingBy { "${it.zone}|${it.local}|${it.key}|${it.lead}" }
            .eachCount().values.sumOf { it - 1 }
        val order = buildString {
            for (index in 0 until minOf(orderWindow, eligible.size)) append(eligible[index].id).append('\n')
        }
        val lines = ArrayList<String>()
        var failed = 0
        fun check(id: String, ok: Boolean) {
            lines.add("CHECK\t${if (ok) "PASS" else "FAIL"}\t$id")
            if (!ok) failed++
        }
        check("MIRROR_DETERMINISTIC_RENDER", text == render())
        check("MIRROR_ROW_COUNT", rows.size == rowCount
            && text.substringAfter('\n').split('\n').count { it.isNotEmpty() } == rowCount)
        check("MIRROR_ELIGIBLE_COUNT", eligible.size == 512)
        check("MIRROR_DUPLICATE_COUNT", duplicates == 128)
        check("MIRROR_EXPIRED_COUNT", rows.count { epoch(it) - it.lead <= now && it.state == "SCHEDULED" } == 128)
        check("MIRROR_FIRED_COUNT", rows.count { it.state == "FIRED" } == 128)
        check("MIRROR_DST_GAP", rows.count {
            it.zone == "America/New_York" && it.local == "2026-03-08T02:30:00"
        } == 128)
        check("MIRROR_DST_OVERLAP", rows.count {
            it.zone == "America/New_York" && it.local == "2026-11-01T01:30:00"
        } == 256)
        check("MIRROR_TZ_BOUNDARY", rows.count(::boundary) == 128)
        check("MIRROR_PAGE_CAP", eligible.size > pageSize && eligible.chunked(pageSize).size == 8)
        check("MIRROR_ORDER_DIGEST", sha256(order.toByteArray(Charsets.US_ASCII)).length == 64)
        check("MIRROR_PRIVACY", !text.contains(marker()) && !text.contains("://"))
        lines.add("DIGEST\t${sha256(bytes)}")
        lines.add("SUMMARY\tchecks=${lines.count { it.startsWith("CHECK\t") }}\tfailed=$failed")
        return lines.joinToString("\n", postfix = "\n") to (failed == 0)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val (report, ok) = run()
        print(report)
        if (!ok) System.exit(1)
    }
}

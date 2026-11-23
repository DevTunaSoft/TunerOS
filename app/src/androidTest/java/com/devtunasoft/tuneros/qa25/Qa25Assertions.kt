package com.devtunasoft.tuneros.qa25

import java.security.MessageDigest

/**
 * QA-25 Android-free mirror of the host gate's catalog generator.
 * It regenerates the pinned scratch catalog byte-for-byte and exposes plain
 * PASS/FAIL lines to both the JVM runner and the instrumented APK.
 * No Android, JUnit or framework import is used here.
 */
object Qa25Assertions {
    const val SEED_HEX = "51413235"
    const val N_RECORDS = 240
    const val WINDOW_SLOTS = 24
    const val LIMIT_CAP = 500
    const val BASE_EPOCH = 1_700_000_000L
    const val PINNED_CATALOG_SHA256 = "2567ef641135cc4f400d58955f1de209cb156be4aff4f75591256bd3ea3545cd"

    val header = "id\ttitle_code\tchannel_id\tstate\tstarted_epoch\tstopped_epoch\tbytes_written\tlocation_code\tfailure_code\tprotected"
    private val states = arrayOf("COMPLETE", "PARTIAL", "RECORDING", "FAILED")
    private val pageLimits = intArrayOf(1, 5, WINDOW_SLOTS, 100, LIMIT_CAP, 3, 7, 12)
    private val pageOffsets = intArrayOf(0, 0, 0, 0, 0, 17, 50, 235)

    private fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')

    private fun startedOf(i: Int) = BASE_EPOCH + (((i * 97) % N_RECORDS) * 37L)

    private fun stateOf(i: Int) = states[(i / 7) % states.size]

    private fun protectedOf(i: Int) = if (i % 11 == 0) "1" else "0"

    private fun rowOf(i: Int): String {
        val started = startedOf(i)
        val state = stateOf(i)
        val stopped = if (state == "RECORDING") "-" else (started + 1800L + (i % 13)).toString()
        val bytes = if (i == 17) "0" else (4096L * (i + 1L)).toString()
        val location = if (state == "RECORDING" || i % 9 == 0) "-" else "loc-" + pad((i * 17) % 53, 5)
        val failure = if (state == "FAILED") "fail-" + (i % 4) else "-"
        return "rec-" + pad(i, 5) +
            "\ttitle-" + pad((i * 7 + 11) % 97, 5) +
            "\tc-" + pad((i * 13 + 3) % 61, 5) +
            "\t" + state + "\t" + started + "\t" + stopped + "\t" + bytes +
            "\t" + location + "\t" + failure + "\t" + protectedOf(i)
    }

    fun catalogBytes(): ByteArray = buildString {
        append(header).append('\n')
        for (i in 0 until N_RECORDS) append(rowOf(i)).append('\n')
    }.toByteArray(Charsets.UTF_8)

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun catalogSha(): String = sha256(catalogBytes())

    private fun columnLines(bytes: ByteArray): List<List<String>> =
        String(bytes, Charsets.UTF_8).lineSequence()
            .filter { it.isNotEmpty() }
            .map { it.split('\t') }
            .toList()

    /** Ordered ids from the regenerated catalog, used to prove the page contract in mirror form. */
    fun sortedIds(): List<String> {
        val rows = columnLines(catalogBytes()).drop(1)
        return rows.sortedWith(
            compareByDescending<List<String>> { it[4].toLong() }.thenBy { it[0] }
        ).map { it[0] }
    }

    fun runChecks(): List<String> {
        val out = ArrayList<String>()
        val digest = catalogSha()
        out.add(marker("MIRROR_REGEN", digest == PINNED_CATALOG_SHA256))

        val rows = columnLines(catalogBytes())
        out.add(marker("MIRROR_HEADER", rows.isNotEmpty() && rows.first().joinToString("\t") == header))
        out.add(marker("MIRROR_ROWS", rows.size == N_RECORDS + 1 && rows.drop(1).all { it.size == 10 }))

        val protected = rows.drop(1).count { it[9] == "1" }
        out.add(marker("MIRROR_PROTECTED", protected == 22))

        val ids = sortedIds()
        val expectedFirst = ids.take(WINDOW_SLOTS)
        val windowOk = expectedFirst.size == WINDOW_SLOTS && expectedFirst.toSet().size == WINDOW_SLOTS
        out.add(marker("MIRROR_WINDOW", windowOk))

        val pageLimitOk = pageLimits.all { it in 1..LIMIT_CAP } &&
            pageLimits.indices.all { p -> pageOffsets[p] >= 0 && pageOffsets[p] <= ids.size }
        out.add(marker("MIRROR_PAGES", pageLimitOk && pageLimits.size == 8))
        return out
    }

    private fun marker(id: String, ok: Boolean) = "CHECK\t" + (if (ok) "PASS" else "FAIL") + "\t" + id

    @JvmStatic
    fun main(args: Array<String>) {
        for (line in runChecks()) println(line)
        println("MIRROR_SHA256\t" + catalogSha())
    }
}

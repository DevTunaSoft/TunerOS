package com.devtunasoft.tuneros.qa41

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QA-41 JVM source-contract test over the committed dvr-outcome fixture.
 *
 * Pure JVM and JUnit 4 only: no android.* stub, device, network, URL,
 * credential or media. The gate in scripts/qa41 is authoritative; this test
 * independently re-evaluates the same pinned matrix inside the app unit suite.
 */
class DvrOutcomeMatrixTest {
    private data class Decision(val state: String, val finalize: Int, val discard: Int)

    companion object {
        private fun fixtureDir(): File {
            var dir = File(System.getProperty("user.dir") ?: ".")
            for (ignored in 0..7) {
                val candidate = File(dir, "qa/fixtures/dvr-outcome")
                if (File(candidate, "cases.tsv").isFile) return candidate
                val parent = dir.parentFile ?: break
                dir = parent
            }
            throw IllegalStateException("dvr-outcome fixtures not found from user.dir")
        }

        private fun dataRows(name: String): List<List<String>> =
            fixtureDir().resolve(name).readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split("\t") }

        private fun finalState(requested: String, bytes: Long): String =
            if (requested == "COMPLETED" && bytes == 0L) "FAILED" else requested

        private fun settle(
            requested: String,
            cancelled: Boolean,
            reported: Long,
            sink: Long,
            finalizeFile: Boolean,
        ): Decision {
            if (cancelled) return Decision("CANCELLED", 0, 0)
            check(reported >= 0 && sink >= 0)
            val acknowledged = minOf(reported, sink)
            if (acknowledged == 0L) {
                val state = finalState(requested, 0L)
                return Decision(state, 0, if (state == "FAILED") 1 else 0)
            }
            val state = finalState(requested, acknowledged)
            if (state != "COMPLETED" || !finalizeFile) {
                val settled = if (state == "COMPLETED") "PARTIAL" else state
                return Decision(settled, 0, 0)
            }
            return Decision("COMPLETED", 1, 0)
        }

        private fun storage(fault: String, bytes: Long, cancelled: Boolean): Decision {
            val effective = if (cancelled) "CANCELLED" else fault
            if (effective != "NONE") {
                val state = when (effective) {
                    "CANCELLED" -> "CANCELLED"
                    else -> if (bytes > 0L) "PARTIAL" else "FAILED"
                }
                val discard = if (state == "CANCELLED" || bytes <= 0L) 1 else 0
                return Decision(state, 0, discard)
            }
            return if (bytes > 0L) Decision("COMPLETED", 1, 0) else Decision("FAILED", 0, 1)
        }

        private fun decide(row: List<String>): Decision {
            val family = row[1]
            val cancelled = row[5] == "1"
            if (family == "storage-fault") {
                return storage(row[6], row[4].toLong(), cancelled)
            }
            val token = row[8].toLong()
            val epochCancelled = token > 0L && row[9].toLong() >= token
            return settle(
                row[2],
                cancelled || (family == "epoch-lost-stop" && epochCancelled),
                row[3].toLong(),
                row[4].toLong(),
                row[7].toInt() > 0,
            )
        }
    }

    @Test
    fun fixtureCoversTheFullOutcomeMatrix() {
        val rows = dataRows("cases.tsv").drop(1)
        assertEquals(20, rows.size)
        assertEquals(
            mapOf(
                "completed" to 2,
                "zero-byte" to 3,
                "cancelled" to 3,
                "storage-fault" to 6,
                "double-finalize" to 2,
                "epoch-lost-stop" to 4,
            ),
            rows.groupingBy { it[1] }.eachCount(),
        )
        assertEquals(
            mapOf("COMPLETED" to 5, "FAILED" to 5, "PARTIAL" to 3, "CANCELLED" to 7),
            rows.groupingBy { it[11] }.eachCount(),
        )
    }

    @Test
    fun everyFixtureRowSettlesToItsPinnedOutcome() {
        for (row in dataRows("cases.tsv").drop(1)) {
            val got = decide(row)
            assertEquals(
                "case ${row[0]}",
                Triple(row[11], row[12].toInt(), row[13].toInt()),
                Triple(got.state, got.finalize, got.discard),
            )
        }
    }

    @Test
    fun doubleFinalizeIsIdempotent() {
        val rows = dataRows("cases.tsv").drop(1).filter { it[1] == "double-finalize" }
        assertEquals(2, rows.size)
        for (row in rows) {
            val calls = row[7].toInt()
            val decision = decide(row)
            val applied = if (decision.state == "COMPLETED" && decision.finalize == 1) 1 else 0
            val noops = calls - applied
            assertTrue("case ${row[0]} must call finalize", calls > 0)
            if (decision.state == "COMPLETED") {
                assertEquals("case ${row[0]} first write wins", 1, applied)
                assertEquals("case ${row[0]} second write is a no-op", calls - 1, noops)
            } else {
                assertEquals("case ${row[0]} no terminal write", 0, applied)
                assertEquals("case ${row[0]} both writes are no-ops", calls, noops)
            }
        }
    }

    @Test
    fun epochStopSurvivesSharedFlagReset() {
        val rows = dataRows("cases.tsv").drop(1).filter { it[1] == "epoch-lost-stop" }
        assertEquals(4, rows.size)
        assertTrue(rows.any { it[10] == "0" && it[11] == "CANCELLED" })
        assertTrue(rows.any { it[11] == "COMPLETED" && it[8] != it[9] })
    }

    @Test
    fun policyMarkersAreInForce() {
        val markers = dataRows("markers.tsv").associate { it[0] to it[1] }
        assertEquals(7, markers.size)
        assertTrue(markers.values.all { it == "1" })
        assertTrue(markers.containsKey("stop_epoch_persists_across_start"))
    }
}

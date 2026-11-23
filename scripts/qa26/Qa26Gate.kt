package com.devtunasoft.tuneros.qa26

import java.lang.management.ManagementFactory
import kotlin.math.abs

/**
 * QA-26 host-only gate. All fixture-driven checks live in the shared
 * Android-free Qa26Contract; this wrapper adds bounded heap and thread
 * sampling and emits the machine-readable status lines the runner parses.
 */
object Qa26Gate {
    private const val HEAP_DRIFT_LIMIT = 8L * 1024 * 1024
    private const val THREAD_LIMIT = 8
    private const val CYCLES = 6

    private fun usedHeap(): Long {
        val bean = ManagementFactory.getMemoryMXBean()
        System.gc()
        Thread.sleep(30L)
        return bean.heapMemoryUsage.used
    }

    private fun threads(): Int = Thread.activeCount()

    private fun cycles(dir: String): Qa26Contract.Check {
        val rows = try {
            Qa26Contract.readTable(
                java.io.File(dir, "cases.tsv").path, Qa26Contract.CASE_HEADER,
            )
        } catch (e: Exception) {
            return Qa26Contract.Check("HEAP_THREADS_BOUNDED", false, "CYCLE_SETUP")
        }
        val before = usedHeap()
        val beforeThreads = threads()
        var insideThreads = -1
        var peakHeap = 0L
        var failed = false
        val worker = Thread({
            try {
                for (cycle in 0 until CYCLES) {
                    var sink = 0
                    for (row in rows) {
                        if (row.size != 13) continue
                        val parsed = Qa26Contract.CaseRow(
                            row[0], row[1], row[2], row[3], row[4].toLongOrNull() ?: 0L,
                            row[5].toLongOrNull() ?: 0L, row[6].toLongOrNull() ?: 0L,
                            row[7].toLongOrNull() ?: 0L, row[8], row[9],
                            row[10].toIntOrNull() ?: 0, row[11], row[12],
                        )
                        val outcome = Qa26Contract.DashPlanProbe.plan(parsed)
                        sink += outcome.length
                    }
                    if (sink <= 0) failed = true
                    if (cycle == 1) insideThreads = threads()
                    if (cycle == 2) peakHeap = usedHeap()
                }
            } catch (t: Throwable) {
                failed = true
            }
        }, "qa26-cycle")
        worker.start()
        val justAfterStart = threads()
        var joined = true
        try {
            worker.join(60_000L)
        } catch (e: InterruptedException) {
            joined = false
            Thread.currentThread().interrupt()
        }
        val after = usedHeap()
        val afterThreads = threads()
        val aliveQa = Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("qa26-") }
        val drift = abs(after - before)
        val ok = joined && !failed && !aliveQa && drift <= HEAP_DRIFT_LIMIT &&
            justAfterStart <= THREAD_LIMIT && insideThreads in 1..THREAD_LIMIT &&
            afterThreads <= beforeThreads + 2 && peakHeap <= HEAP_DRIFT_LIMIT
        return Qa26Contract.Check(
            "HEAP_THREADS_BOUNDED", ok,
            "drift=$drift limit=$HEAP_DRIFT_LIMIT threads=$afterThreads/$justAfterStart/$insideThreads",
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrElse(0) { "verify" }
        val dir = args.getOrElse(1) { "qa/fixtures/qa26" }
        if (mode == "generate") {
            val status = Qa26Contract.generate(dir)
            println("GENERATE\tstatus=$status")
            System.exit(if (status == 0) 0 else 1)
        }
        val results = try {
            Qa26Contract.checks(dir, mode) + cycles(dir)
        } catch (e: Exception) {
            listOf(Qa26Contract.Check("GATE_EXCEPTION", false, e.javaClass.simpleName))
        }
        for (r in results) {
            val state = if (r.ok) "PASS" else "FAIL"
            if (r.note.isEmpty()) println("CHECK\t$state\t${r.code}")
            else println("CHECK\t$state\t${r.code}\t${r.note}")
        }
        val failures = results.count { !it.ok }
        val first = results.firstOrNull { !it.ok }?.code ?: "NONE"
        val pass = results.size - failures
        println("SUMMARY\tchecks=${results.size}\tpass=$pass\tfail=$failures\tfirst=$first")
        System.exit(if (failures == 0) 0 else 1)
    }
}

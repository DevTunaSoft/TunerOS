package com.devtunasoft.tuneros.qa28

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs

/** QA-28 host wrapper: bounded heap/thread sampling and machine-readable status lines. */
object Qa28Gate {
    private const val HEAP_DRIFT_LIMIT = 8L * 1024 * 1024
    private const val THREAD_LIMIT = 8
    private const val CYCLES = 6

    private fun usedHeap(): Long {
        val bean = ManagementFactory.getMemoryMXBean()
        System.gc()
        Thread.sleep(30L)
        return bean.heapMemoryUsage.used
    }

    private fun cycles(dir: String): Qa28Contract.Check {
        val before = usedHeap()
        val beforeThreads = Thread.activeCount()
        var insideThreads = -1
        var failed = false
        val worker = Thread({
            try {
                repeat(CYCLES) { cycle ->
                    val results = Qa28Contract.checks(dir, "mutant")
                    if (results.isEmpty() || results.any { !it.ok }) failed = true
                    if (cycle == 1) insideThreads = Thread.activeCount()
                }
            } catch (t: Throwable) {
                failed = true
            }
        }, "qa28-cycle")
        worker.start()
        val justAfterStart = Thread.activeCount()
        var joined = true
        try {
            worker.join(60_000L)
        } catch (e: InterruptedException) {
            joined = false
            Thread.currentThread().interrupt()
        }
        val after = usedHeap()
        val afterThreads = Thread.activeCount()
        val leaked = Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("qa28-") }
        val drift = abs(after - before)
        val ok = joined && !failed && !leaked && drift <= HEAP_DRIFT_LIMIT &&
            justAfterStart <= THREAD_LIMIT && insideThreads in 1..THREAD_LIMIT &&
            afterThreads <= beforeThreads + 2
        return Qa28Contract.Check(
            "HEAP_THREADS_BOUNDED", ok,
            "drift=$drift limit=$HEAP_DRIFT_LIMIT threads=$afterThreads/$justAfterStart/$insideThreads",
        )
    }

    private fun mirrorParity(regen: String): Qa28Contract.Check {
        if (regen.isBlank()) return Qa28Contract.Check("MIRROR_PARITY", false, "no-scratch")
        return try {
            val dir = File(regen, "mirror").absolutePath
            val results = Qa28Contract.mirror(dir)
            Qa28Contract.Check(
                "MIRROR_PARITY", results.isNotEmpty() && results.all { it.ok },
                "checks=${results.size}",
            )
        } catch (e: Exception) {
            Qa28Contract.Check("MIRROR_PARITY", false, e.javaClass.simpleName)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrElse(0) { "verify" }
        val dir = args.getOrElse(1) { "qa/fixtures/qa28" }
        val regen = args.getOrElse(2) { "" }
        if (mode == "generate") {
            println("GENERATE\tstatus=${Qa28Contract.generate(dir)}")
            System.exit(if (File(dir, "sha256.txt").isFile) 0 else 1)
        }
        val results = try {
            if (mode == "mirror") Qa28Contract.mirror(dir)
            else Qa28Contract.checks(dir, mode, regen) + cycles(dir) + mirrorParity(regen)
        } catch (e: Exception) {
            listOf(Qa28Contract.Check("GATE_EXCEPTION", false, e.javaClass.simpleName))
        }
        for (r in results) {
            val state = if (r.ok) "PASS" else "FAIL"
            if (r.note.isEmpty()) println("CHECK\t$state\t${r.code}")
            else println("CHECK\t$state\t${r.code}\t${r.note}")
        }
        val failures = results.count { !it.ok }
        val first = results.firstOrNull { !it.ok }?.code ?: "NONE"
        println("SUMMARY\tchecks=${results.size}\tpass=${results.size - failures}\tfail=$failures\tfirst=$first")
        System.exit(if (failures == 0) 0 else 1)
    }
}

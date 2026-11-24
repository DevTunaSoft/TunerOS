package com.devtunasoft.tuneros.qa53

import java.io.File
import kotlin.system.exitProcess

/** QA-53 bounded offline host gate over the real compiled PlaylistParser. */
object Qa53HostMain {
    private val modes = setOf("generate", "verify", "verify-mutant", "verify-pinned")

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2 || args[0] !in modes) {
            println("USAGE\tqa53\tgenerate|verify|verify-mutant|verify-pinned|verify\tDIR")
            exitProcess(2)
        }
        val mode = args[0]
        val dir = File(args[1])
        val lines = ArrayList<String>(40)
        fun emit(line: String) {
            lines.add(line)
            println(line)
        }
        val threadsBefore = Thread.activeCount()
        if (mode == "generate") {
            if (!dir.isDirectory && !dir.mkdirs()) {
                emit("VERDICT\tFAIL\tfirst=DIR_CREATE")
                exitProcess(1)
            }
            for ((name, bytes) in Qa53Contract.generateFiles()) {
                File(dir, name).writeBytes(bytes)
                emit("GENERATE\t$name\tbytes=${bytes.size}")
            }
            emit("VERDICT\tPASS\tfiles=${Qa53Contract.FILE_COUNT}")
            return
        }

        val skip = when (mode) {
            "verify-mutant" -> setOf("MANIFEST_CONSISTENT", "PINNED_DIGEST", "BYTE_IDENTITY")
            "verify-pinned" -> setOf("MANIFEST_CONSISTENT", "BYTE_IDENTITY")
            else -> emptySet()
        }
        val files = LinkedHashMap<String, ByteArray>()
        for (name in Qa53Contract.ALL_FILES) {
            val file = File(dir, name)
            if (!file.isFile) {
                emit("VERDICT\tFAIL\tfirst=MISSING_FILE")
                exitProcess(1)
            }
            files[name] = file.readBytes()
        }

        var failures = 0
        var checks = 0
        var first = "NONE"
        val result = try {
            Qa53Contract.runChecks(files, skip)
        } catch (error: Throwable) {
            emit("CHECK\tHOST_EXCEPTION\tFAIL\tclass=${error.javaClass.simpleName}")
            checks++
            failures++
            first = "HOST_EXCEPTION"
            null
        }
        if (result != null) {
            for (check in result.checks) {
                val detail = if (check.detail.isEmpty()) "" else "\t${check.detail}"
                emit("CHECK\t${check.code}\t${if (check.ok) "PASS" else "FAIL"}$detail")
                checks++
                if (!check.ok) {
                    failures++
                    if (first == "NONE") first = check.code
                }
            }
        }
        val offenders = Qa53Contract.privacyOffenders(lines.joinToString("\n"))
        val diagnosticsOk = offenders.isEmpty()
        emit("CHECK\tDIAGNOSTIC_SCAN\t${if (diagnosticsOk) "PASS" else "FAIL"}\toffenders=${offenders.size}")
        checks++
        if (!diagnosticsOk) {
            failures++
            if (first == "NONE") first = "DIAGNOSTIC_SCAN"
        }
        val threadsAfter = Thread.activeCount()
        val runtime = Runtime.getRuntime()
        emit("MEM\theapUsed=${runtime.totalMemory() - runtime.freeMemory()}\theapMax=${runtime.maxMemory()}")
        emit("THREADS\tstart=$threadsBefore\tend=$threadsAfter\tdrift=${threadsAfter - threadsBefore}")
        if (threadsAfter != threadsBefore) {
            emit("CHECK\tTHREAD_DRIFT\tFAIL\tstart=$threadsBefore end=$threadsAfter")
            checks++
            failures++
            if (first == "NONE") first = "THREAD_DRIFT"
        } else {
            emit("CHECK\tTHREAD_DRIFT\tPASS\tdrift=0")
            checks++
        }
        emit("FIRST\t$first")
        emit("SUMMARY\tchecks=$checks\tfail=$failures\tfirst=$first")
        emit("VERDICT\t${if (failures == 0) "PASS" else "FAIL"}\tfirst=$first")
        exitProcess(if (failures == 0) 0 else 1)
    }
}

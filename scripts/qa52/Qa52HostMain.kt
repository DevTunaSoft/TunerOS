package com.devtunasoft.tuneros.qa52

import java.io.File
import kotlin.system.exitProcess

/** QA-52 bounded offline host gate over the real compiled PlayerReusePolicy. */
object Qa52HostMain {
    private val MODES = setOf("generate", "verify", "verify-mutant")

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2 || args[0] !in MODES) {
            println("USAGE\tqa52\tgenerate|verify|verify-mutant\tDIR")
            exitProcess(2)
        }
        val mode = args[0]
        val dir = File(args[1])
        val lines = ArrayList<String>(32)
        fun emit(line: String) {
            lines.add(line)
            println(line)
        }
        if (mode == "generate") {
            if (!dir.isDirectory && !dir.mkdirs()) {
                emit("VERDICT\tFAIL\tfirst=DIR_CREATE")
                exitProcess(1)
            }
            for ((name, bytes) in Qa52Contract.generateFiles()) {
                File(dir, name).writeBytes(bytes)
                emit("GENERATE\t$name\tbytes=${bytes.size}")
            }
            emit("VERDICT\tPASS\tfiles=${Qa52Contract.FILE_COUNT}")
            return
        }

        val skip = if (mode == "verify-mutant") {
            setOf("MANIFEST_CONSISTENT", "PINNED_DIGEST", "BYTE_IDENTITY")
        } else {
            emptySet()
        }
        val expectedNames = Qa52Contract.generateFiles().keys
        val files = LinkedHashMap<String, ByteArray>()
        for (name in expectedNames) {
            val file = File(dir, name)
            if (!file.isFile) {
                emit("VERDICT\tFAIL\tfirst=MISSING_FILE")
                exitProcess(1)
            }
            files[name] = file.readBytes()
        }

        var first = "NONE"
        var failures = 0
        var checks = 0
        val result = try {
            Qa52Contract.runChecks(files, skip)
        } catch (error: Throwable) {
            emit("CHECK\tHOST_EXCEPTION\tFAIL\tclass=${error.javaClass.simpleName}")
            checks++
            failures++
            if (first == "NONE") first = "HOST_EXCEPTION"
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
        val offenders = Qa52Contract.privacyOffenders(lines.joinToString("\n"))
        val diagnosticsOk = offenders.isEmpty()
        emit("CHECK\tDIAGNOSTIC_SCAN\t${if (diagnosticsOk) "PASS" else "FAIL"}\toffenders=${offenders.size}")
        checks++
        if (!diagnosticsOk) {
            failures++
            if (first == "NONE") first = "DIAGNOSTIC_SCAN"
        }
        emit("FIRST\t$first")
        emit("SUMMARY\tchecks=$checks\tfail=$failures\tfirst=$first")
        emit("VERDICT\t${if (failures == 0) "PASS" else "FAIL"}\tfirst=$first")
        exitProcess(if (failures == 0) 0 else 1)
    }
}

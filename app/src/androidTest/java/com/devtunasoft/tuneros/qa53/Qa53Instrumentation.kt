package com.devtunasoft.tuneros.qa53

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumented mirror: regenerates the fixture and drives the shared gate checks. */
class Qa53Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val files = Qa53Contract.generateFiles()
        val result = Qa53Contract.runChecks(files, emptySet())
        val lines = ArrayList<String>(24)
        fun add(code: String, ok: Boolean) {
            if (lines.size < 24) lines.add("CHECK\t$code\t${if (ok) "PASS" else "FAIL"}")
        }
        add("FILE_COUNT", files.size == Qa53Contract.FILE_COUNT)
        for (check in result.checks) add(check.code, check.ok)
        add("PRIVACY_SCAN_DIAG", Qa53Contract.privacyOffenders(
            lines.joinToString("\n")
        ).isEmpty())

        val failures = lines.count { it.endsWith("\tFAIL") }
        val report = File(targetContext.filesDir, "tuneros-qa53-report.txt")
        report.writeText(lines.joinToString("\n", postfix = "\n"))
        val bundle = Bundle()
        bundle.putInt("checks", lines.size)
        bundle.putInt("failures", failures)
        finish(if (failures == 0) 0 else 1, bundle)
    }
}

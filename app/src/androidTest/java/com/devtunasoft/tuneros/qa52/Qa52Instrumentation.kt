package com.devtunasoft.tuneros.qa52

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumented mirror. It regenerates the fixture and drives the shared gate checks. */
class Qa52Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val files = Qa52Contract.generateFiles()
        val result = Qa52Contract.runChecks(files, emptySet())
        val lines = ArrayList<String>(24)
        fun add(code: String, ok: Boolean) {
            if (lines.size < 24) lines.add("CHECK\t$code\t${if (ok) "PASS" else "FAIL"}")
        }
        add("FILE_COUNT", files.size == Qa52Contract.FILE_COUNT)
        for (check in result.checks) add(check.code, check.ok)
        add("PRIVACY_SCAN", Qa52Contract.privacyOffenders(
            files.values.joinToString("\n") { String(it, Charsets.ISO_8859_1) }
        ).isEmpty())

        val failures = lines.count { it.endsWith("\tFAIL") }
        val report = File(targetContext.filesDir, "tuneros-qa52-report.txt")
        report.writeText(lines.joinToString("\n", postfix = "\n"))
        val bundle = Bundle()
        bundle.putInt("checks", lines.size)
        bundle.putInt("failures", failures)
        finish(if (failures == 0) 0 else 1, bundle)
    }
}

package com.devtunasoft.tuneros.qa24

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-24 bounded instrumentation wrapper. The assertions live in Qa24Contract,
 * which has no Android imports and is also compiled and run on the host JVM.
 * Only check ids, counts and digests are written to the report.
 */
class Qa24Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val lines = Qa24Contract.runChecks()
        val failures = lines.count { it.startsWith("FAIL\t") }
        val checks = lines.count { it.startsWith("PASS\t") || it.startsWith("FAIL\t") }
        val summary = lines.lastOrNull() ?: "SUMMARY\tpass=0\tfail=0"
        val report = lines.joinToString("\n", postfix = "\n")

        val file = File(targetContext.filesDir, "qa24-report.txt")
        file.writeText(report)

        val result = Bundle()
        result.putInt("qa24_checks", checks)
        result.putInt("qa24_failures", failures)
        result.putString("qa24_summary", summary)
        finish(if (failures == 0) 0 else 1, result)
    }
}

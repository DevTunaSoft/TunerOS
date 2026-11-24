package com.devtunasoft.tuneros.qa47

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Compiled-only instrumentation wrapper; device execution requires an authorized target. */
class Qa47Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = Qa47Mirror.runChecks()
        val failures = lines.count { it.startsWith("FAIL") }
        val report = lines.joinToString("\n") + "\n"
        File(targetContext.filesDir, "qa47-report.txt").writeText(report)
        val result = Bundle().apply {
            putInt("qa47_total", lines.size)
            putInt("qa47_failures", failures)
        }
        finish(if (failures == 0) 0 else 1, result)
    }
}

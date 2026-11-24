package com.devtunasoft.tuneros.qa48

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Compiled-only instrumentation wrapper; device execution requires an authorized target. */
class Qa48Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = Qa48Mirror.runChecks()
        val failures = lines.count { it.startsWith("FAIL") }
        val report = lines.joinToString("\n") + "\n"
        File(targetContext.filesDir, "qa48-report.txt").writeText(report)
        val result = Bundle().apply {
            putInt("qa48_total", lines.size)
            putInt("qa48_failures", failures)
        }
        finish(if (failures == 0) 0 else 1, result)
    }
}

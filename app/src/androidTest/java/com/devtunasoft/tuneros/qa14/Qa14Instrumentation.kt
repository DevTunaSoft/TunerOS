package com.devtunasoft.tuneros.qa14

import android.app.Instrumentation
import android.os.Bundle

/** QA-14 device entry point. Device execution requires an authorized device. */
class Qa14Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        Thread({
            try {
                val (report, ok) = Qa14Assertions.run()
                val file = java.io.File(targetContext.filesDir, "qa14-report.txt")
                file.writeText(report)
                val checks = report.lines().count { it.startsWith("CHECK\tPASS\t") }
                val failures = report.lines().count { it.startsWith("CHECK\tFAIL\t") }
                val result = Bundle()
                result.putInt("checks", checks)
                result.putInt("failures", failures)
                finish(if (ok) 0 else 1, result)
            } catch (error: Exception) {
                val result = Bundle()
                result.putInt("checks", 0)
                result.putInt("failures", 1)
                finish(1, result)
            }
        }, "TunerOS-QA14").start()
    }
}

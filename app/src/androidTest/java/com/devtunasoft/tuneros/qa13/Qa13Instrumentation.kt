package com.devtunasoft.tuneros.qa13

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded, dependency-free instrumentation wrapper. Pure assertions live in Qa13Assertions. */
class Qa13Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = Qa13Assertions.reportLines()
        val passed = lines.count { it.startsWith("CHECK\tPASS") }
        val failed = lines.count { it.startsWith("CHECK\tFAIL") }
        val report = File(targetContext.filesDir, "qa13-report.txt")
        report.writeText(lines.joinToString("\n", postfix = "\n"))
        val result = Bundle()
        result.putString("qa13-result", if (failed == 0 && passed > 0) "PASS" else "FAIL")
        result.putInt("qa13-checks-passed", passed)
        result.putInt("qa13-checks-failed", failed)
        finish(if (failed == 0 && passed > 0) 0 else 1, result)
    }
}

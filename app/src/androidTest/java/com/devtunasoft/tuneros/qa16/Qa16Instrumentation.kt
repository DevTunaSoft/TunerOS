package com.devtunasoft.tuneros.qa16

import android.app.Instrumentation
import android.os.Bundle

/** Bounded, dependency-free instrumentation wrapper; assertions live in Qa16Assertions. */
class Qa16Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = Qa16Assertions.reportLines()
        val passed = lines.count { it.startsWith("CHECK\tPASS\t") }
        val failed = lines.count { it.startsWith("CHECK\tFAIL\t") }
        val result = Bundle()
        result.putString("qa16-result", if (failed == 0 && passed > 0) "PASS" else "FAIL")
        result.putInt("qa16-checks-passed", passed)
        result.putInt("qa16-checks-failed", failed)
        finish(if (failed == 0 && passed > 0) 0 else 1, result)
    }
}

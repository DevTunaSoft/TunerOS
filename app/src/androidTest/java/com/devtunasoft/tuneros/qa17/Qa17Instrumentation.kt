package com.devtunasoft.tuneros.qa17

import android.app.Instrumentation
import android.os.Bundle

/** Bounded, dependency-free instrumentation wrapper; assertions live in Qa17Assertions. */
class Qa17Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = Qa17Assertions.reportLines()
        val passed = lines.count { it.startsWith("CHECK\tPASS\t") }
        val failed = lines.count { it.startsWith("CHECK\tFAIL\t") }
        val result = Bundle()
        result.putString("qa17-result", if (failed == 0 && passed > 0) "PASS" else "FAIL")
        result.putInt("qa17-checks-passed", passed)
        result.putInt("qa17-checks-failed", failed)
        finish(if (failed == 0 && passed > 0) 0 else 1, result)
    }
}

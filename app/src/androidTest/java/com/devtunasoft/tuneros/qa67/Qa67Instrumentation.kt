package com.devtunasoft.tuneros.qa67

import android.app.Instrumentation
import android.os.Bundle

/** Bounded instrumented runner: the mirror checks are pure CPU work and run inline after start(). */
class Qa67Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val report = StringBuilder()
        val summary = Qa67Contract.runAll { line, _ -> report.append(line).append('\n') }
        val result = Bundle()
        result.putInt("qa67.pass", summary.pass)
        result.putInt("qa67.fail", summary.fail)
        try {
            targetContext.filesDir.resolve("qa67-report.txt").writeText(report.toString())
        } catch (_: Exception) {
            // The result bundle is authoritative; a full scratch filesystem must not hide the verdict.
        }
        finish(if (summary.fail == 0) 0 else 1, result)
    }
}

package com.devtunasoft.tuneros.qa22

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle

/** Bounded QA-22 instrumented runner: pure CPU checks, one report file, no device-only dependency. */
class Qa22Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = Qa22Contract.runChecks()
        for (line in lines) println(line)
        val report = lines.joinToString("\n") + "\n"
        targetContext.openFileOutput("qa22-report.txt", Context.MODE_PRIVATE).use { stream ->
            stream.write(report.toByteArray(Charsets.UTF_8))
        }
        val result = Bundle()
        result.putString("qa22Report", "qa22-report.txt")
        val failed = lines.count { it.startsWith("CHECK\tFAIL") }
        finish(if (failed == 0) 0 else 1, result)
    }
}

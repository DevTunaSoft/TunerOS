package com.devtunasoft.tuneros.qa23

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle

/** Bounded QA-23 instrumented runner: pure CPU checks over the production sidx parser. */
class Qa23Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = Qa23Contract.runChecks()
        for (line in lines) println(line)
        val report = lines.joinToString("\n") + "\n"
        targetContext.openFileOutput("qa23-report.txt", Context.MODE_PRIVATE).use { stream ->
            stream.write(report.toByteArray(Charsets.UTF_8))
        }
        val result = Bundle()
        result.putString("qa23Report", "qa23-report.txt")
        val failed = lines.count { it.startsWith("CHECK\tFAIL") }
        finish(if (failed == 0) 0 else 1, result)
    }
}

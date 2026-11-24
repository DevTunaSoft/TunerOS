package com.devtunasoft.tuneros.qa65

import android.app.Instrumentation
import android.os.Bundle

/** Bounded instrumentation runner: runs the Android-free mirror and publishes a small report. */
class Qa65Runner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        Thread({
            val lines = Qa65Assertions.runChecks()
            val report = lines.joinToString("\n", postfix = "\n")
            targetContext.openFileOutput("qa65_report.txt", 0).use {
                it.write(report.toByteArray(Charsets.UTF_8))
            }
            val ok = lines.isNotEmpty() && lines.all { it.startsWith("PASS") }
            finish(if (ok) 0 else 1, Bundle())
        }, "qa65-runner").start()
    }
}

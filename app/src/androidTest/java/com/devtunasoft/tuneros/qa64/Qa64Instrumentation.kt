package com.devtunasoft.tuneros.qa64

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * Bounded instrumented mirror of the QA-64 contract. It contains no JUnit or AndroidX reference
 * and runs inline: all work is CPU-only and bounded by the fixture dimensions.
 */
class Qa64Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val result = Qa64Assertions.runChecks()
        val report = File(targetContext.filesDir, "qa64-report.txt")
        report.writeText(result.lines.joinToString("\n", postfix = "\n"))
        val outcome = Bundle()
        outcome.putInt("qa64_checks", 8)
        outcome.putInt("qa64_failures", result.lines.count { it.contains("\tFAIL\t") })
        finish(if (result.ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, outcome)
    }
}

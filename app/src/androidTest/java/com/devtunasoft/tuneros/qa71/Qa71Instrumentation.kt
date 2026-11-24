package com.devtunasoft.tuneros.qa71

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** QA-71 bounded instrumented runner. The assertions are Android-free in Qa71Contract. */
class Qa71Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val (report, ok) = Qa71Contract.run()
        File(targetContext.filesDir, "qa71-report.txt").writeText(report)
        val result = Bundle()
        result.putInt("checks", 8)
        result.putString("report", report)
        finish(if (ok) 0 else 1, result)
    }
}

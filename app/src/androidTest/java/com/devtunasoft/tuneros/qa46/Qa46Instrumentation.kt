package com.devtunasoft.tuneros.qa46

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-46 instrumented mirror. Compiled by the offline AGP gate only; execution
 * needs an authorized device and is not claimed here.
 */
class Qa46Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val report = StringBuilder()
        val sink = object : Qa46Contract.Sink {
            override fun line(value: String) {
                report.append(value).append('\n')
            }
        }
        val verdict = Qa46Contract.runAll(sink)

        File(targetContext.filesDir, "qa46-report.txt").writeText(report.toString(), Charsets.UTF_8)

        val result = Bundle()
        result.putInt("checks", verdict.checks)
        result.putInt("pass", verdict.passed)
        result.putInt("fail", verdict.failed)
        result.putString("first", verdict.first ?: "NONE")
        finish(if (verdict.ok) 0 else 1, result)
    }
}

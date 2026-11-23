package com.devtunasoft.tuneros.qa19

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-19 instrumented wrapper. The assertion body lives in the framework-free
 * Qa19Contract object, which is also compiled into the host gate. No JUnit,
 * AndroidX or network access is used.
 */
class Qa19Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val report = StringBuilder()
        val sink = object : Qa19Contract.Sink {
            override fun line(value: String) {
                report.append(value).append('\n')
            }
        }
        val verdict = Qa19Contract.runAll(null, sink, null)

        val output = File(targetContext.filesDir, "qa19-report.txt")
        output.writeText(report.toString(), Charsets.UTF_8)

        val result = Bundle()
        result.putInt("checks", verdict.checks)
        result.putInt("pass", verdict.passed)
        result.putInt("fail", verdict.failed)
        result.putString("first", verdict.first ?: "NONE")
        finish(if (verdict.ok) 0 else 1, result)
    }
}

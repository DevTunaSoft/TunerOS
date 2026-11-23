package com.devtunasoft.tuneros.qa20

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-20 instrumented wrapper. All assertions live in the framework-free Qa20Contract
 * object that is also compiled into the host gate. No JUnit, AndroidX or network use.
 * Device execution is not implied by this compiled entry point.
 */
class Qa20Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val report = StringBuilder()
        val sink = object : Qa20Contract.Sink {
            override fun line(value: String) {
                report.append(value).append('\n')
            }
        }
        val verdict = Qa20Contract.runAll(null, sink, null)

        val output = File(targetContext.filesDir, "qa20-report.txt")
        output.writeText(report.toString(), Charsets.UTF_8)

        val result = Bundle()
        result.putInt("checks", verdict.checks)
        result.putInt("pass", verdict.passed)
        result.putInt("fail", verdict.failed)
        result.putString("first", verdict.first ?: "NONE")
        finish(if (verdict.ok) 0 else 1, result)
    }
}

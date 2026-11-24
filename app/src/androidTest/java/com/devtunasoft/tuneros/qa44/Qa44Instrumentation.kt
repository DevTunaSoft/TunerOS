package com.devtunasoft.tuneros.qa44

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-44 instrumented mirror. Shares the framework-free contract with the host gate and uses no
 * JUnit, AndroidX or network access. Device execution requires an authorized target.
 */
class Qa44Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val report = StringBuilder()
        val sink = object : Qa44Contract.Sink {
            override fun line(value: String) {
                report.append(value).append('\n')
            }
        }
        val scratch = File(targetContext.cacheDir, "qa44-runtime")
        scratch.deleteRecursively()
        val verdict = Qa44Contract.runAll(null, scratch, sink)

        File(targetContext.filesDir, "qa44-report.txt").writeText(report.toString(), Charsets.UTF_8)
        scratch.deleteRecursively()

        val result = Bundle()
        result.putInt("checks", verdict.checks)
        result.putInt("pass", verdict.passed)
        result.putInt("fail", verdict.failed)
        result.putString("first", verdict.first ?: "NONE")
        finish(if (verdict.ok) 0 else 1, result)
    }
}

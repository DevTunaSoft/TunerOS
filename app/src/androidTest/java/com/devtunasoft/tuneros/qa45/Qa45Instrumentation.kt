package com.devtunasoft.tuneros.qa45

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-45 instrumented mirror. Device execution requires an authorized target;
 * this class is compiled by the offline AGP gate only unless a target exists.
 */
class Qa45Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val report = StringBuilder()
        val sink = object : Qa45Contract.Sink {
            override fun line(value: String) {
                report.append(value).append('\n')
            }
        }
        val verdict = Qa45Contract.runAll(sink)

        File(targetContext.filesDir, "qa45-report.txt").writeText(report.toString(), Charsets.UTF_8)

        val result = Bundle()
        result.putInt("checks", verdict.checks)
        result.putInt("pass", verdict.passed)
        result.putInt("fail", verdict.failed)
        result.putString("first", verdict.first ?: "NONE")
        finish(if (verdict.ok) 0 else 1, result)
    }
}

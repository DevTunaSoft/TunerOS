package com.devtunasoft.tuneros.qa21

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-21 instrumented wrapper. Every assertion lives in the framework-free Qa21Contract
 * object that is also compiled into the host gate. There is no JUnit, AndroidX, network
 * access or production-state mutation here. Compiling this class does not imply that a
 * device run happened: device execution needs an attached authorized target.
 */
class Qa21Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val lines = Qa21Contract.deviceChecks()
        val report = StringBuilder()
        var passed = 0
        var failed = 0
        var first = "NONE"
        for (line in lines) {
            report.append(line).append('\n')
            val fields = line.split('\t')
            if (fields.size >= 3 && fields[0] == "CHECK") {
                if (fields[1] == "PASS") passed++ else if (fields[1] == "FAIL") {
                    failed++
                    if (first == "NONE") first = fields[2]
                }
            }
        }

        val output = File(targetContext.filesDir, "qa21-report.txt")
        output.writeText(report.toString(), Charsets.UTF_8)

        val result = Bundle()
        result.putInt("checks", passed + failed)
        result.putInt("pass", passed)
        result.putInt("fail", failed)
        result.putString("first", first)
        finish(if (failed == 0 && passed == 10) 0 else 1, result)
    }
}

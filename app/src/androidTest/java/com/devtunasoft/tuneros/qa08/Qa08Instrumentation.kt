package com.devtunasoft.tuneros.qa08

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-08 platform instrumentation runner. No JUnit/AndroidX dependency: the same named assertions
 * run on the JVM and here, and the report is read back by the bounded shell runner.
 */
class Qa08Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val checks = Qa08Assertions.runAll()
        val lines = ArrayList<String>(checks.size + 1)
        var passed = 0
        for (check in checks) {
            lines.add("${if (check.pass) "PASS" else "FAIL"}\t${check.id}\t${check.detail}")
            if (check.pass) passed++
        }
        lines.add("$passed PASS\t${checks.size - passed} FAIL")
        File(targetContext.filesDir, "qa08-large-dvr-report.txt").writeText(lines.joinToString("\n") + "\n")
        val result = Bundle()
        result.putInt("qa08_total", checks.size)
        result.putInt("qa08_failures", checks.size - passed)
        finish(if (passed == checks.size) 0 else 1, result)
    }
}

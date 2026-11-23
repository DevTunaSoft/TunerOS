package com.devtunasoft.tuneros.qa11

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-11 on-device runner. It uses only the Android framework: no JUnit, AndroidX or test dependencies.
 * The assertion object is framework-free and also runs on the JVM before the device attempt.
 */
class Qa11Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val results = Qa11Assertions.run()
        val report = buildString {
            for (result in results) {
                append(if (result.pass) "PASS" else "FAIL").append('\t').append(result.id)
                    .append('\t').append(result.detail).append('\n')
            }
            val passed = results.count { it.pass }
            append("SUMMARY\t").append(passed).append('/').append(results.size).append('\n')
        }
        File(targetContext.filesDir, "qa11-report.txt").writeText(report)
        val passed = results.count { it.pass }
        val bundle = Bundle().apply {
            putInt("checks", results.size)
            putInt("passed", passed)
        }
        finish(if (passed == results.size) 0 else 1, bundle)
    }
}

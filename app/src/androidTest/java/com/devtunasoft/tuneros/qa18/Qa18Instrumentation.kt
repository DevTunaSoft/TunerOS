package com.devtunasoft.tuneros.qa18

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-18 instrumented mirror. No JUnit, no AndroidX and no framework imports beyond
 * Instrumentation/Bundle. All assertions live in the Android-free Qa18Assertions object, which the
 * host gate also compiles and runs; this class only orchestrates and records the result.
 */
class Qa18Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        Thread({ runChecks() }, "qa18-instrumentation").start()
    }

    private fun runChecks() {
        val lines = ArrayList<String>(Qa18Assertions.MAX_DIAGNOSTIC_LINES + 2)
        var verdict = "PASS"
        try {
            for (line in Qa18Assertions.run()) lines.add(line)
        } catch (failure: Qa18Failure) {
            verdict = "FAIL"
            lines.add("FIRST\t${failure.code}\t${failure.detail}")
        } catch (error: Throwable) {
            verdict = "FAIL"
            lines.add("FIRST\tUNEXPECTED\t${error.javaClass.simpleName}")
        } finally {
            val summary = if (verdict == "PASS") {
                "SUMMARY\t${lines.size}/${lines.size}\tPASS"
            } else {
                "SUMMARY\tFAIL\t${lines.size}"
            }
            val report = (lines + summary).joinToString("\n") + "\n"
            File(targetContext.filesDir, "qa18-diagnostics-report.txt").writeText(report)
            val results = Bundle()
            results.putString("qa18.summary", summary)
            finish(if (verdict == "PASS") 0 else 1, results)
        }
    }
}

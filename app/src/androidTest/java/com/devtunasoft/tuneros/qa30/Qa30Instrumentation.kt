package com.devtunasoft.tuneros.qa30

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumentation wrapper; all assertions live in the Android-free mirror. */
class Qa30Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val results = try {
            Qa30Assertions.mirror()
        } catch (error: Exception) {
            listOf(Qa30Assertions.Check("MIRROR_EXCEPTION", false, error.javaClass.simpleName))
        }

        val report = StringBuilder()
        for (result in results) {
            val state = if (result.ok) "PASS" else "FAIL"
            report.append("CHECK\t").append(state).append('\t').append(result.code).append('\n')
        }
        val passed = results.count { it.ok }
        report.append("SUMMARY\tchecks=").append(results.size)
            .append("\tpass=").append(passed)
            .append("\tfail=").append(results.size - passed).append('\n')

        try {
            File(targetContext.filesDir, "qa30-report.txt").writeText(report.toString())
        } catch (error: Exception) {
            // The result bundle still carries a bounded copy of the report.
        }
        val bundle = Bundle()
        bundle.putString("qa30.report", report.toString().take(2048))
        finish(if (passed == results.size && results.isNotEmpty()) 0 else 1, bundle)
    }
}

package com.devtunasoft.tuneros.qa29

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * Bounded instrumentation wrapper. All assertions live in the shared
 * Android-free Qa29Contract, so the same source runs on the JVM host gate and
 * on a device. This class only runs the mirror and writes a bounded report.
 */
class Qa29Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val results = try {
            Qa29Contract.mirror()
        } catch (e: Exception) {
            listOf(Qa29Contract.Check("MIRROR_EXCEPTION", false, e.javaClass.simpleName))
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
            File(targetContext.filesDir, "qa29-report.txt").writeText(report.toString())
        } catch (e: Exception) {
            // The result bundle still carries a bounded copy of the report.
        }
        val bundle = Bundle()
        bundle.putString("qa29.report", report.toString().take(2048))
        finish(if (passed == results.size && results.isNotEmpty()) 0 else 1, bundle)
    }
}

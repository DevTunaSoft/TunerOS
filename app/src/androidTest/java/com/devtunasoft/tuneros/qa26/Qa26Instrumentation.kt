package com.devtunasoft.tuneros.qa26

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * Bounded instrumentation wrapper. The checks themselves live in the shared
 * Android-free Qa26Contract, so the same source runs on the JVM and on device.
 * This class only generates scratch data, invokes the mirror, writes a bounded
 * report and returns a result bundle.
 */
class Qa26Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val results = try {
            val dir = File(targetContext.filesDir, "qa26-scratch")
            val generated = Qa26Contract.generate(dir.absolutePath)
            if (generated == 0) Qa26Contract.mirror(dir.absolutePath)
            else listOf(Qa26Contract.Check("MIRROR_GENERATE", false, "status=$generated"))
        } catch (e: Exception) {
            listOf(Qa26Contract.Check("MIRROR_EXCEPTION", false, e.javaClass.simpleName))
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
            File(targetContext.filesDir, "qa26-report.txt").writeText(report.toString())
        } catch (e: Exception) {
            // The result bundle still carries a bounded copy of the report.
        }
        val bundle = Bundle()
        bundle.putString("qa26.report", report.toString().take(2048))
        finish(if (passed == results.size && results.isNotEmpty()) 0 else 1, bundle)
    }
}

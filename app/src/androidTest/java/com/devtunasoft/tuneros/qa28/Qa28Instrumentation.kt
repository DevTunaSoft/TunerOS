package com.devtunasoft.tuneros.qa28

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * Bounded instrumentation wrapper. All checks live in the shared Android-free
 * Qa28Contract, so the same source runs on the JVM host gate and on a device.
 * This class only generates scratch data and writes a bounded report.
 */
class Qa28Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val results = try {
            val dir = File(targetContext.filesDir, "qa28-scratch")
            Qa28Contract.mirror(dir.absolutePath)
        } catch (e: Exception) {
            listOf(Qa28Contract.Check("MIRROR_EXCEPTION", false, e.javaClass.simpleName))
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
            File(targetContext.filesDir, "qa28-report.txt").writeText(report.toString())
        } catch (e: Exception) {
            // The result bundle still carries a bounded copy of the report.
        }
        val bundle = Bundle()
        bundle.putString("qa28.report", report.toString().take(2048))
        finish(if (passed == results.size && results.isNotEmpty()) 0 else 1, bundle)
    }
}

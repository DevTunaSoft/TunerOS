package com.devtunasoft.tuneros.qa69

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded on-device runner. The worker thread starts from onCreate, not from an overridden run(). */
class Qa69Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val worker = Thread({
            val result = Qa69Mirror.runChecks()
            val report = result.first
            val payload = Bundle()
            payload.putString("qa69_report", report)
            payload.putInt("qa69_checks", report.lines().count { it.startsWith("CHECK\t") })
            try {
                File(targetContext.filesDir, "qa69-report.txt").writeText(report)
            } catch (_: Exception) {
                // Result-bundle delivery still works when filesDir is unavailable.
            }
            finish(if (result.second) 0 else 1, payload)
        }, "qa69-instrumentation")
        worker.start()
    }
}

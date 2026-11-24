package com.devtunasoft.tuneros.qa70

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded on-device runner. The worker thread is started from onCreate; run() is not overridden. */
class Qa70Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val worker = Thread({
            val result = Qa70MirrorMain.run()
            val report = result.first
            val payload = Bundle()
            payload.putString("qa70_report", report)
            payload.putInt("qa70_checks", report.lines().count { it.startsWith("CHECK") })
            try {
                File(targetContext.filesDir, "qa70-report.txt").writeText(report)
            } catch (_: Exception) {
                // The result bundle still carries the report when filesDir is unavailable.
            }
            finish(if (result.second) 0 else 1, payload)
        }, "qa70-instrumentation")
        worker.start()
    }
}

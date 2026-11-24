package com.devtunasoft.tuneros.qa68

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded on-device runner. On Android 15 the thread is started from onCreate, not by overriding run(). */
class Qa68Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val worker = Thread({
            val result = Qa68MirrorMain.run()
            val report = result.first
            val payload = Bundle()
            payload.putString("qa68_report", report)
            payload.putInt("qa68_checks", report.lines().count { it.startsWith("CHECK\t") })
            try {
                File(targetContext.filesDir, "qa68-report.txt").writeText(report)
            } catch (_: Exception) {
                // Report delivery through the result bundle still works when filesDir is unavailable.
            }
            finish(if (result.second) 0 else 1, payload)
        }, "qa68-instrumentation")
        worker.start()
    }
}

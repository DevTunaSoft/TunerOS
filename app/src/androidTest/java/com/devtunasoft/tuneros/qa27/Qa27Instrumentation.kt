package com.devtunasoft.tuneros.qa27

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Platform-only instrumentation runner. No JUnit or AndroidX dependency. */
class Qa27Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val worker = Thread({
            val results = Qa27Assertions.run()
            val pass = results.count { it.second }
            val lines = buildString {
                for ((id, ok) in results) append(id).append('\t').append(if (ok) "PASS" else "FAIL").append('\n')
                append("SUMMARY\tPASS=").append(pass).append("\tFAILED=").append(results.size - pass).append('\n')
            }
            try {
                File(targetContext.filesDir, "qa27-report.txt").writeText(lines)
            } catch (_: Throwable) {
                // Report write is best-effort; the result bundle is authoritative.
            }
            val out = Bundle()
            out.putInt("qa27_pass", pass)
            out.putInt("qa27_failed", results.size - pass)
            finish(if (pass == results.size) 0 else 1, out)
        }, "QA27-assert")
        worker.isDaemon = true
        worker.start()
    }
}

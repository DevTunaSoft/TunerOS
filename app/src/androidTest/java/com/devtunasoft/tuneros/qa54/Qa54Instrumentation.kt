package com.devtunasoft.tuneros.qa54

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumented mirror. It runs the same framework-free checks as the host gate. */
class Qa54Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = ArrayList<String>()
        val rc = try {
            Qa54Contract.run("verify-device", targetContext.filesDir) { line -> lines.add(line) }
        } catch (t: Throwable) {
            lines.add("FIRST\tINSTRUMENTATION_EXCEPTION")
            lines.add("EXCEPTION\t" + t.javaClass.simpleName)
            1
        }
        val report = lines.joinToString("\n")
        File(targetContext.filesDir, "qa54-report.txt").writeText(report + "\n")
        val result = Bundle().apply { putString("qa54_report", report) }
        finish(if (rc == 0) 0 else 1, result)
    }
}

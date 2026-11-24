package com.devtunasoft.tuneros.qa55

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumented mirror: regenerates scratch data and runs the framework-free checks. */
class Qa55Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = ArrayList<String>()
        val rc = try {
            val dir = File(targetContext.filesDir, "qa55-device-fixture")
            dir.mkdirs()
            for ((name, bytes) in Qa55Contract.generate()) File(dir, name).writeBytes(bytes)
            Qa55Contract.verifyModel("verify", HashMap(Qa55Contract.generate())) { line -> lines.add(line) }
        } catch (t: Throwable) {
            lines.add("FIRST\tINSTRUMENTATION_EXCEPTION")
            lines.add("EXCEPTION\t" + t.javaClass.simpleName)
            -1
        }
        val passCount = lines.count { it.startsWith("CHECK\t") && it.contains("\tPASS\t") }
        lines.add("SUMMARY\tchecks=" + passCount + "\tfail=" + if (rc < 0) 1 else 0)
        lines.add("VERDICT\t" + if (rc >= 0) "PASS" else "FAIL")
        val report = lines.joinToString("\n")
        File(targetContext.filesDir, "qa55-report.txt").writeText(report + "\n")
        val result = Bundle().apply { putString("qa55_report", report) }
        finish(if (rc >= 0) 0 else 1, result)
    }
}

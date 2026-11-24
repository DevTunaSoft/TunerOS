package com.devtunasoft.tuneros.qa56

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumented mirror: regenerates scratch data and runs the framework-free checks. */
class Qa56Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = ArrayList<String>()
        val rc = try {
            val dir = File(targetContext.filesDir, "qa56-device-fixture")
            dir.mkdirs()
            val generated = Qa56Contract.generate()
            for ((name, bytes) in generated) File(dir, name).writeBytes(bytes)
            val files = LinkedHashMap<String, ByteArray>()
            for (name in Qa56Contract.ALL_FILES) files[name] = File(dir, name).readBytes()
            Qa56Contract.verifyModel("verify", files) { line -> lines.add(line) }
        } catch (t: Throwable) {
            lines.add("FIRST\tINSTRUMENTATION_EXCEPTION")
            lines.add("EXCEPTION\t" + t.javaClass.simpleName)
            -1
        }
        val passCount = lines.count { it.startsWith("CHECK\t") && it.contains("\tPASS\t") }
        lines.add("SUMMARY\tchecks=" + passCount + "\tfail=" + if (rc < 0) 1 else 0)
        lines.add("VERDICT\t" + if (rc >= 0) "PASS" else "FAIL")
        val report = lines.joinToString("\n")
        File(targetContext.filesDir, "qa56-report.txt").writeText(report + "\n")
        val result = Bundle().apply { putString("qa56_report", report) }
        finish(if (rc >= 0) 0 else 1, result)
    }
}

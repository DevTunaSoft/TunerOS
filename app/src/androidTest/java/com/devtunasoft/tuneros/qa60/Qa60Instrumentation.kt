package com.devtunasoft.tuneros.qa60

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumented mirror: regenerates scratch bytes and runs the framework-free checks. */
class Qa60Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val lines = ArrayList<String>()
        val rc = try {
            val dir = File(targetContext.filesDir, "qa60-device-fixture")
            dir.mkdirs()
            val generated = Qa60Contract.generate()
            for ((name, bytes) in generated) File(dir, name).writeBytes(bytes)
            val files = LinkedHashMap<String, ByteArray>()
            for (name in Qa60Contract.ALL_FILES) files[name] = File(dir, name).readBytes()
            Qa60Contract.verifyModel("verify", files) { line -> lines.add(line) }
        } catch (t: Throwable) {
            lines.add("FIRST\tINSTRUMENTATION_EXCEPTION")
            lines.add("EXCEPTION\t" + t.javaClass.simpleName)
            -1
        }
        val passCount = lines.count { it.startsWith("CHECK\t") && it.endsWith("\tPASS") }
        val failCount = lines.count { it.startsWith("CHECK\t") && it.endsWith("\tFAIL") }
        lines.add("SUMMARY\tchecks=$passCount\tfail=$failCount")
        lines.add("VERDICT\t" + if (rc == 0) "PASS" else "FAIL")
        val report = lines.joinToString("\n")
        File(targetContext.filesDir, "qa60-report.txt").writeText(report + "\n")
        val result = Bundle().apply { putString("qa60_report", report) }
        finish(if (rc == 0) 0 else 1, result)
    }
}

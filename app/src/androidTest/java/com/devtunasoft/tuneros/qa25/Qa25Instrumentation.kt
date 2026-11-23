package com.devtunasoft.tuneros.qa25

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * QA-25 instrumentation wrapper. The assertion object is Android-free and is
 * also run on the JVM by the host gate; this class only orchestrates and reports.
 */
class Qa25Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val lines = Qa25Assertions.runChecks()
        val passes = lines.count { it.startsWith("CHECK\tPASS") }
        val fails = lines.count { it.startsWith("CHECK\tFAIL") }
        val body = lines.joinToString("\n", postfix = "\n")

        var writeError = ""
        try {
            File(targetContext.filesDir, "qa25-report.txt").writeText(body)
        } catch (t: Throwable) {
            writeError = t.javaClass.simpleName
        }

        val result = Bundle()
        result.putInt("passes", passes)
        result.putInt("fails", fails)
        result.putString("catalog_sha256", Qa25Assertions.catalogSha())
        result.putString("write_error", writeError)
        finish(if (fails == 0 && writeError.isEmpty()) 0 else 1, result)
    }
}

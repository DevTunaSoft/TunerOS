package com.devtunasoft.tuneros.qa63

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * Bounded instrumented mirror. Generates the same pinned fixture into app scratch
 * storage, drives the real GuideDoctor from the app under test, writes a short
 * PASS/FAIL report and finishes with the gate's exit code. No JUnit or AndroidX.
 */
class Qa63Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val dir = File(targetContext.filesDir, "qa63-fixture")
        val code = try {
            Qa63Contract.generateTo(dir)
            Qa63Contract.run(arrayOf("gate-only", dir.absolutePath))
        } catch (e: Exception) {
            1
        }
        try {
            File(targetContext.filesDir, "qa63-report.txt").writeText(Qa63Contract.lastReport())
        } catch (e: Exception) {
            // Report write is best effort; the result bundle still carries the verdict.
        }
        val result = Bundle()
        result.putInt("qa63_code", code)
        finish(code, result)
    }
}

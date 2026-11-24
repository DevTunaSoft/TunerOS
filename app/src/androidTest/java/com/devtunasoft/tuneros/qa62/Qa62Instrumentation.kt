package com.devtunasoft.tuneros.qa62

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Bounded instrumented mirror: runs the framework-free contract on scratch fixture bytes. */
class Qa62Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val dir = File(targetContext.filesDir, "qa62-scratch")
        val report = File(targetContext.filesDir, "qa62-report.txt")
        val lines = ArrayList<String>()
        var pass = 0
        var fail = 0
        try {
            dir.mkdirs()
            Qa62Contract.writeFiles(dir)
            for (check in Qa62Contract.mirrorChecks(dir)) {
                val status = if (check.ok) "PASS" else "FAIL"
                if (check.ok) pass++ else fail++
                lines += "CHECK\t${check.code}\t$status\t${check.detail}"
            }
        } catch (error: Throwable) {
            fail++
            lines += "CHECK\tMIRROR_EXCEPTION\tFAIL\t${error.javaClass.simpleName}"
        }
        lines += "SUMMARY\tpass=$pass\tfail=$fail"
        lines += "VERDICT\t${if (fail == 0) "PASS" else "FAIL"}"
        report.writeText(lines.joinToString("\n") + "\n")
        val result = Bundle()
        result.putInt("pass", pass)
        result.putInt("fail", fail)
        finish(if (fail == 0) 0 else 1, result)
    }
}

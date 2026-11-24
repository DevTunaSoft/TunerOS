package com.devtunasoft.tuneros.qa49

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * Bounded instrumented mirror of the host gate. It checks that the framework-free contract
 * regenerates byte-identical fixture data on device and writes a small PASS/FAIL report.
 * Device execution is only meaningful with an authorized adb target.
 */
class Qa49Runner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val lines = ArrayList<String>(32)
        fun check(id: String, ok: Boolean) {
            if (lines.size < 32) lines.add("CHECK\t$id\t${if (ok) "PASS" else "FAIL"}")
        }

        val files = Qa49Contract.generate()
        check("GENERATE_FILES", files.size == 5)
        val casesText = files.getValue("cases.tsv")
        check("CASE_COUNT", Qa49Contract.parseCases(casesText).size == Qa49Contract.CASES)
        check(
            "CASES_DIGEST",
            Qa49Contract.sha256(casesText.toByteArray(Charsets.UTF_8)) ==
                Qa49Contract.parseDimensions(files.getValue("dimensions.tsv")).getValue("casesSha256"),
        )
        check("MARKER_DERIVED", Qa49Contract.marker().startsWith("QA49M-") && Qa49Contract.marker().length == 18)
        check(
            "PRIVACY_SCAN",
            Qa49Contract.privacyOffenders(files.values.joinToString("\n")).isEmpty(),
        )

        val failures = lines.count { it.endsWith("\tFAIL") }
        val report = File(targetContext.filesDir, "tuneros-qa49-report.txt")
        report.writeText(lines.joinToString("\n", postfix = "\n"))
        val result = Bundle()
        result.putInt("checks", lines.size)
        result.putInt("failures", failures)
        finish(if (failures == 0) 0 else 1, result)
    }
}

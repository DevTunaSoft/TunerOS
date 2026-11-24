package com.devtunasoft.tuneros.qa50

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/**
 * Bounded instrumented mirror of the QA-50 host gate. It regenerates the scratch contract
 * on device and checks its digests and privacy hygiene. Device execution needs an authorized
 * adb target and is never claimed from a compile alone.
 */
class Qa50Runner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()

        val lines = ArrayList<String>(24)
        fun check(id: String, ok: Boolean) {
            if (lines.size < 24) lines.add("CHECK\t$id\t${if (ok) "PASS" else "FAIL"}")
        }

        val files = Qa50Contract.generateFiles()
        check("FILE_COUNT", files.size == Qa50Contract.FILE_COUNT)
        val casesText = String(files.getValue("cases.tsv"), Charsets.UTF_8)
        check("CASE_COUNT", Qa50Contract.parseCases(casesText).size == Qa50Contract.CASES)
        val dimensions = Qa50Contract.parseDimensions(String(files.getValue("dimensions.tsv"), Charsets.UTF_8))
        check(
            "CASES_DIGEST",
            Qa50Contract.sha256(files.getValue("cases.tsv")) == dimensions.getValue("casesSha256"),
        )
        check(
            "GUIDE_DIGEST",
            Qa50Contract.sha256(files.getValue("guide.xmltv")) == dimensions.getValue("guideSha256"),
        )
        check("MARKER_DERIVED", Qa50Contract.marker().startsWith("QA50M-") && Qa50Contract.marker().length == 14)
        check(
            "PRIVACY_SCAN",
            Qa50Contract.privacyOffenders(files.values.joinToString("\n") { String(it, Charsets.ISO_8859_1) }).isEmpty(),
        )

        val failures = lines.count { it.endsWith("\tFAIL") }
        val report = File(targetContext.filesDir, "tuneros-qa50-report.txt")
        report.writeText(lines.joinToString("\n", postfix = "\n"))
        val result = Bundle()
        result.putInt("checks", lines.size)
        result.putInt("failures", failures)
        finish(if (failures == 0) 0 else 1, result)
    }
}

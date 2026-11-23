package com.devtunasoft.tuneros.qa06

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * QA-06 instrumentation entry point. It runs the same deterministic large-EPG assertions as
 * scripts/qa06/Qa06LargeEpgGate.java on the device: byte-identical 25,000-channel / 250,000-
 * programme scratch fixture, bounded page indices and row counts, contiguous visible rows,
 * stable ordering and report hygiene. No AndroidX/JUnit dependency, no network and no app data.
 *
 * adb shell am instrument -w -r \
 *   com.devtunasoft.tuneros.test/com.devtunasoft.tuneros.qa06.Qa06LargeEpgInstrumentation
 */
class Qa06LargeEpgInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread({ runChecks() }, "qa06-large-epg-runner").apply { isDaemon = false }.start()
    }

    private fun runChecks() {
        val results = try {
            Qa06LargeEpgAssertions.runAll()
        } catch (error: Throwable) {
            listOf(Qa06Result("qa06-runner", false, error.message ?: error.javaClass.simpleName))
        }
        val failures = results.count { !it.passed }
        val report = buildString {
            append("QA-06 large EPG gate | device=").append(Build.MODEL)
                .append(" | checks=").append(results.size).append(" | failures=").append(failures).append('\n')
            for (result in results) {
                append(if (result.passed) "PASS" else "FAIL").append('\t')
                    .append(result.id).append('\t').append(result.detail).append('\n')
            }
            append("SUMMARY\t").append(results.size - failures).append(" PASS\t")
                .append(failures).append(" FAIL\n")
        }
        results.forEach { Log.i(TAG, "${if (it.passed) "PASS" else "FAIL"}\t${it.id}\t${it.detail}") }
        runCatching { File(targetContext.filesDir, REPORT_NAME).writeText(report) }
        finish(
            if (failures == 0 && results.size == REQUIRED_CHECKS) Activity.RESULT_OK else 1,
            Bundle().apply {
                putInt("qa06_checks", results.size)
                putInt("qa06_failures", failures)
            },
        )
    }

    private companion object {
        const val TAG = "Qa06LargeEpgQA"
        const val REPORT_NAME = "qa06-large-epg-report.txt"
        const val REQUIRED_CHECKS = 7
    }
}

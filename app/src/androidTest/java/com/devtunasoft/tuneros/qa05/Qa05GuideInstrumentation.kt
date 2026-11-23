package com.devtunasoft.tuneros.qa05

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/**
 * QA-05 instrumentation entry point. It encodes the same deterministic assertions as
 * scripts/qa05/Qa05FixtureVerifier.java: the guide's case-insensitive trimmed name search, the
 * user-controlled hide-no-EPG filter (channels with no programmes hidden, future-only schedules
 * still visible, no guide snapshot hides everything), and the objective view-bound and focus
 * expectations (one channel cell and one timeline per visible row, first selectable programme
 * focused, gaps skipped by RIGHT/LEFT, LEFT at the first programme requesting a window shift).
 *
 * No androidx.test or JUnit dependency is needed: this uses the Android framework's
 * Instrumentation API only. Results go to logcat and to the instrumentation result Bundle.
 * Run with: adb shell am instrument -w com.devtunasoft.tuneros.test/com.devtunasoft.tuneros.qa05.Qa05GuideInstrumentation
 */
class Qa05GuideInstrumentation : Instrumentation() {
    override fun onRun(arguments: Bundle) {
        val results = Qa05GuideAssertions.runAll()
        val failures = results.count { !it.passed }
        val report = buildString {
            results.forEach {
                append(if (it.passed) "PASS" else "FAIL").append('\t')
                    .append(it.id).append('\t').append(it.detail).append('\n')
            }
            append("QA05 INSTRUMENTATION ").append(if (failures == 0) "PASS" else "FAIL")
                .append(": ").append(results.size - failures).append('/').append(results.size)
                .append(" checks\n")
        }
        results.forEach {
            Log.i(TAG, "${if (it.passed) "PASS" else "FAIL"}\t${it.id}\t${it.detail}")
        }
        val result = Bundle().apply {
            putInt("qa05.checks", results.size)
            putInt("qa05.failures", failures)
            putString("qa05.report", report)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private companion object {
        const val TAG = "Qa05GuideQA"
    }
}

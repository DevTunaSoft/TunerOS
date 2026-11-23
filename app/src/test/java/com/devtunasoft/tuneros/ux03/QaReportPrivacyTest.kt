package com.devtunasoft.tuneros.ux03

import android.os.Handler
import com.devtunasoft.tuneros.BackupQa
import com.devtunasoft.tuneros.ParentalQa
import com.devtunasoft.tuneros.TimeshiftQa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-03 negative regression tests for the three debug QA report writers. Every value below is a
 * synthetic marker; no real credential, provider URL, feed, path or recording is used. The tests run
 * the real QA classes on the JVM with synthetic Android stubs and in-memory stores.
 */
class QaReportPrivacyTest {

    private fun scratchDir(): File =
        File("C:/Users/X1/AppData/Local/hermes/cache/scratch/ux03-qa-" + System.nanoTime()).also { it.mkdirs() }

    private fun assertHygiene(text: String, context: FakeQaContext, minLines: Int, maxLines: Int) {
        val lines = text.lines().filter { it.isNotBlank() }
        assertTrue("Report has too few checks: ${lines.size} lines: $text", lines.size >= minLines)
        assertTrue("Report grew past $maxLines lines: ${lines.size}", lines.size <= maxLines)
        assertTrue("A report line exceeded 200 characters", lines.none { it.length > 200 })
        assertFalse("A PIN must never reach the report", text.contains("1234"))
        assertFalse("A wrong PIN must never reach the report", text.contains("0000"))
        assertFalse("A synthetic credential marker leaked: $text", text.contains(SECRET))
        assertFalse("A URL leaked: $text", text.contains("://"))
        assertFalse("A credential label leaked: $text", text.contains("token=", ignoreCase = true))
        assertFalse("A file path leaked: $text", text.contains(context.filesDir.absolutePath))
        assertFalse("A file URI leaked: $text", text.contains("file://"))
    }

    @Test
    fun parentalReportIsRedactedBoundedAndOrdered() {
        val dir = scratchDir()
        try {
            val context = FakeQaContext(dir)
            val report = File(dir, "tuneros-parental-report.txt")
            ParentalQa.run(context)
            val first = report.readText()
            assertHygiene(first, context, 8, 16)
            assertFalse(
                "Preference keys must not reach the report",
                listOf("restricted-groups", "restricted-channels", "locked-until", "\"failures\"", "\"hash\"")
                    .any { first.contains(it) },
            )
            assertFalse("A salted hash must not be echoed", first.matches(Regex("(?s).*[A-Za-z0-9+/]{24,}={0,2}.*")))

            ParentalQa.run(context)
            assertEquals("A second run must write the same lines in the same order", first, report.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backupReportIsRedactedBoundedAndOrdered() {
        val dir = scratchDir()
        try {
            val context = FakeQaContext(dir)
            val report = File(dir, "tuneros-backup-report.txt")
            BackupQa.run(context)
            val first = report.readText()
            assertHygiene(first, context, 5, 8)
            assertFalse("The hostile fixture URL must not be echoed", first.contains("qa.example"))
            assertFalse("The hostile fixture filename must not be echoed", first.contains("list.m3u8"))
            assertFalse("Backup payload values must not be echoed", first.contains("qa-channel"))

            BackupQa.run(context)
            assertEquals("A second run must write the same lines in the same order", first, report.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun timeshiftReportIsRedactedBoundedAndOrdered() {
        val dir = scratchDir()
        try {
            val context = FakeQaContext(dir)
            val report = File(dir, "tuneros-timeshift-report.txt")
            val url = "http://qa.invalid/ux03-live.m3u8?token=$SECRET"
            var played = false
            val qa = TimeshiftQa(report, File(dir, "ring"), Handler(), playLocal = { _, onFinished ->
                played = true
                onFinished("PASS")
            })

            qa.run(url, 4)
            val first = report.readText()
            assertHygiene(first, context, 3, 6)
            assertFalse("The synthetic host must not be echoed", first.contains("qa.invalid"))
            assertFalse("The local playlist must not be played on an early failure", played)

            qa.run(url, 4)
            assertEquals("A second run must write the same lines in the same order", first, report.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val SECRET = "SYNTHETIC_UX03_MARKER"
    }
}

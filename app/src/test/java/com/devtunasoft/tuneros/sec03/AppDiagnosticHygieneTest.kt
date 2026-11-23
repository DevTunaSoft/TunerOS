package com.devtunasoft.tuneros.sec03

import com.devtunasoft.tuneros.OnDeviceSelfTest
import com.devtunasoft.tuneros.UpgradeQa
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideDoctor
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.StreamDoctor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-03 negative regressions for app-owned diagnostics. Every marker below is synthetic: no real
 * provider URL, credential, PIN, private path, DRM key or recording payload appears in this file.
 *
 * These tests guard the shareable sinks that a user can hand to a third party. The production checks
 * audited here write fixed detail literals; the guide-summary and content-type sinks handle strings
 * that originate outside the app, so they are exercised with adversarial synthetic values.
 */
class AppDiagnosticHygieneTest {
    companion object {
        private const val FAKE_URL = "https://example.invalid/qa?token=SYNTHETIC_TOKEN"
        private const val FAKE_PATH = "/data/user/0/com.devtunasoft.tuneros/files/SYNTHETIC_PRIVATE"
        private const val FAKE_DRM = "SYNTHETIC_DRM_KEY_9f1c"
        private const val FAKE_PIN = "SYNTHETIC_PIN_7381"
        private const val FAKE_PAYLOAD = "SYNTHETIC_RECORDING_PAYLOAD"
        private val MARKERS = listOf(FAKE_URL, FAKE_PATH, FAKE_DRM, FAKE_PIN, FAKE_PAYLOAD)
        private const val SHARE_REPORT_LIMIT = 8 * 1024
    }

    /** Details are the fixed strings the real runner emits, not values read from sources or files. */
    private fun productionLikeChecks(): List<OnDeviceSelfTest.Check> = listOf(
        OnDeviceSelfTest.Check("previous-test-run", OnDeviceSelfTest.State.PASS, "No interrupted self-test detected"),
        OnDeviceSelfTest.Check(
            "source-recovery-policy", OnDeviceSelfTest.State.PASS,
            "No prior source overwritten by failed, empty, stale or edited imports",
        ),
        OnDeviceSelfTest.Check("offline-m3u-parser", OnDeviceSelfTest.State.PASS, "One synthetic channel parsed"),
        OnDeviceSelfTest.Check("offline-xmltv-dtd-block", OnDeviceSelfTest.State.PASS, "DTD and entity injection rejected"),
        OnDeviceSelfTest.Check("scratch-dvr-copy", OnDeviceSelfTest.State.PASS, "1 KiB direct-to-disk test, temp file removed"),
        OnDeviceSelfTest.Check("android-keystore-roundtrip", OnDeviceSelfTest.State.PASS, "Dedicated encrypted canary saved/read/removed"),
        OnDeviceSelfTest.Check(
            "exact-dvr-alarm-access", OnDeviceSelfTest.State.BLOCKED,
            "Android has not granted exact alarms; no permission prompts issued",
        ),
        OnDeviceSelfTest.Check(
            "external-recording-storage", OnDeviceSelfTest.State.NOT_RUN,
            "No optional removable/shared recording destination selected",
        ),
        OnDeviceSelfTest.Check("playlist-source-loaded", OnDeviceSelfTest.State.PASS, "Imported channel list exists"),
        OnDeviceSelfTest.Check(
            "live-provider-playback", OnDeviceSelfTest.State.NOT_RUN,
            "No real provider stream has reached isPlaying in retained observations",
        ),
        OnDeviceSelfTest.Check(
            "dvr-rearm", OnDeviceSelfTest.State.PASS,
            "2 pending exact alarm(s) submitted; firing not yet verified",
        ),
        OnDeviceSelfTest.Check(
            "debug-guide-doctor", OnDeviceSelfTest.State.PASS,
            "Guide Doctor finds unscheduled channels, applies a saved mapping, suggests only scheduled guide channels, " +
                "counts unused mappings, finds ended schedules and builds a shareable summary",
        ),
        OnDeviceSelfTest.Check(
            "reboot-usb-and-long-run", OnDeviceSelfTest.State.NOT_RUN,
            "Physical power cuts, USB removal and extended RAM stress cannot be simulated safely",
        ),
    )

    @Test
    fun shareableSelfTestReportHasFixedOrderAndNoSensitiveMarkers() {
        val checks = productionLikeChecks()
        val report = OnDeviceSelfTest.Report(1_700_000_000_000L, 987_654L, 35, 100_000L, checks)
        val text = report.text()
        val lines = text.replace("\r\n", "\n").trimEnd('\n').lines()

        assertEquals("lines=$lines", 6 + checks.size, lines.size)
        assertEquals("TunerOS device self-test (no private URLs or provider headers)", lines[0])
        assertEquals("Wall clock (epoch ms): 1700000000000", lines[1])
        assertEquals("Elapsed since boot (ms): 987654", lines[2])
        assertEquals("Android API: 35", lines[3])
        assertEquals("Process PSS snapshot (KiB): 100000 (not a memory benchmark)", lines[4])
        assertEquals("Autonomous QA: INCOMPLETE | 9 PASS / 0 FAIL / 1 BLOCKED / 3 NOT RUN", lines[5])
        checks.forEachIndexed { index, check ->
            assertEquals("${check.state}\t${check.id}\t${check.detail}", lines[6 + index])
        }
        for (marker in MARKERS) {
            assertFalse("shareable report leaked $marker", text.contains(marker, ignoreCase = true))
        }
        assertEquals("repeated rendering must be identical", text, report.text())
        assertEquals("Autonomous QA: INCOMPLETE | 9 PASS / 0 FAIL / 1 BLOCKED / 3 NOT RUN", report.summary())
        assertTrue(text.length < SHARE_REPORT_LIMIT)
    }

    @Test
    fun selfTestReportStaysUnderTheShareCapForAFullCheckBudget() {
        val checks = (0 until 60).map { index ->
            OnDeviceSelfTest.Check("qa-check-$index", OnDeviceSelfTest.State.PASS, "fixed synthetic detail $index")
        } + productionLikeChecks()
        val text = OnDeviceSelfTest.Report(1_000L, 1_000L, 35, 1_000L, checks).text()
        assertEquals(6 + checks.size, text.replace("\r\n", "\n").trimEnd('\n').lines().count())
        assertTrue("report exceeded the 8 KiB share cap", text.length < SHARE_REPORT_LIMIT)
    }

    @Test
    fun diagnosticSummaryDropsSyntheticUrlAndCapsExternalWarnings() {
        val channels = listOf(Channel("c1", "Synthetic Name", FAKE_URL))
        val guide = GuideSnapshot(
            listOf(GuideProgram("g1", "Synthetic Show", 0L, 3_600L)),
            mapOf("g1" to "Synthetic Name"),
        )
        val diagnosis = GuideDoctor.diagnose(channels, emptyMap(), guide, 1_000L)
        val warnings = listOf("1 programmes lacked timezone: assumed UTC", FAKE_URL, "X".repeat(400))
        val text = GuideDoctor.summaryText(diagnosis, warnings, 1_000L)

        assertFalse(text.contains("Synthetic Name"))
        assertFalse("synthetic provider URL leaked into summary", text.contains(FAKE_URL, ignoreCase = true))
        assertTrue(text.contains("- 1 programmes lacked timezone: assumed UTC"))
        for (line in text.lineSequence().filter { it.startsWith("- ") }) {
            assertTrue("warning line is unbounded", line.length <= 202)
        }
        assertEquals("summary rendering must be identical", text, GuideDoctor.summaryText(diagnosis, warnings, 1_000L))
    }

    @Test
    fun playbackEvidenceContentTypeSanitizerDropsParametersAndUrls() {
        assertNull(StreamDoctor.safeContentType(FAKE_URL))
        assertNull(StreamDoctor.safeContentType(FAKE_DRM))
        assertNull(StreamDoctor.safeContentType(FAKE_PAYLOAD))
        assertEquals("video/mp4", StreamDoctor.safeContentType("video/mp4; token=$FAKE_PIN"))
        assertEquals(null, StreamDoctor.safeContentType(null))
    }

    @Test
    fun upgradeCheckPinIsSyntheticAndAbsentFromTheShareableReport() {
        assertEquals("1234", UpgradeQa.PIN)
        val text = OnDeviceSelfTest.Report(1_000L, 1_000L, 35, 1_000L, productionLikeChecks()).text()
        assertFalse("the QA PIN must never appear in a shareable report", text.contains(UpgradeQa.PIN))
    }

    @Test
    fun eventLogAndShareBudgetsAreBounded() {
        val eventBudget = OnDeviceSelfTest::class.java
            .getDeclaredField("MAX_EVENTS_BYTES").apply { isAccessible = true }.getInt(null)
        val shareBudget = OnDeviceSelfTest::class.java
            .getDeclaredField("MAX_SHARE_EVENTS").apply { isAccessible = true }.getInt(null)
        assertTrue("event log budget grew", eventBudget in 1..(32 * 1024))
        assertTrue("share budget grew", shareBudget in 1..SHARE_REPORT_LIMIT)
        assertTrue("share must not exceed the event log", shareBudget <= eventBudget)
    }
}

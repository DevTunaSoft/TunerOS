package com.devtunasoft.tuneros.dvr06

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DVR-06 source contracts: the per-recording cancellation epoch must be wired into the
 * service boundary, the worker fault path and the finalization decision. A pure unit test
 * of the epoch alone cannot prove that a STOP actually reaches it, so these checks assert
 * the owned production source at the four call sites that matter.
 *
 * Source text only; no android.* stub, device, URL, credential or media.
 */
class DvrFinalizationEpochContractTest {

    private val source: String get() = Dvr06Sources.service()

    private fun body(signature: String): String = Dvr06Sources.member(source, signature)

    @Test
    fun stopCommandMarksTheActiveRecordingBeforeSettingTheSharedFlag() {
        val compact = Dvr06Sources.compact(source)
        val guard = "ACTION_STOP -> { cancellationEpoch.cancelActive() cancelled.set(true)"
        assertTrue("STOP must record the epoch before resetting the shared flag", compact.contains(guard))
    }

    @Test
    fun anAdmittedStartOpensAFreshCancellationEpoch() {
        val body = Dvr06Sources.compact(body("private fun startRecording("))
        assertTrue(
            "An admitted recording needs its own token",
            body.contains("val recordingEpoch = cancellationEpoch.beginRecording()"),
        )
        assertTrue(
            "The token must reach the capture worker",
            body.contains("capture(id, url, endEpochSeconds, userAgent, referer, recordingEpoch)"),
        )
    }

    @Test
    fun finalizationConsultsTheEpochBeforePromotingAnOutcome() {
        val body = body("private fun applyOutcome(")
        assertTrue(
            "A STOP on this recording must block COMPLETED at finalization",
            body.contains("cancellationEpoch.isCancelled(recordingEpoch)"),
        )
    }

    @Test
    fun faultPathConsultsTheEpochWhenClassifyingTheTerminalState() {
        val body = body("private fun capture(")
        assertTrue(
            "An exception after STOP must still be classified as cancelled for that recording",
            body.contains("cancellationEpoch.isCancelled(recordingEpoch)"),
        )
    }

    @Test
    fun serviceDestructionMarksTheActiveRecordingCancelled() {
        val body = Dvr06Sources.compact(body("override fun onDestroy()"))
        assertTrue(
            "onDestroy must cancel the active recording epoch as well as the shared flag",
            body.contains("cancellationEpoch.cancelActive()"),
        )
    }
}

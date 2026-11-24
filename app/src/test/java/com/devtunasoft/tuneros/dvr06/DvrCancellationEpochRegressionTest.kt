package com.devtunasoft.tuneros.dvr06

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DVR-06: a STOP must stay terminal for the recording it stopped.
 *
 * The service used one shared AtomicBoolean for cancellation and reset it on every accepted
 * START. If recording A is stopped and recording B starts before A's worker finishes, A sees
 * cancellation=false at finalization and can be promoted to COMPLETED. The per-recording epoch
 * keeps the STOP of A scoped to A; B gets a fresh, uncancelled epoch.
 *
 * The epoch is loaded reflectively so this exact test source also compiles on the unmodified
 * base, where the production seam does not exist (that is the RED). Fixtures are synthetic
 * Long epochs only: no URL, credential, media, android.* stub or device state.
 */
class DvrCancellationEpochRegressionTest {

    private fun epochInstance(): Any = try {
        Class.forName("com.devtunasoft.tuneros.DvrCancellationEpoch")
            .declaredConstructors.first().apply { isAccessible = true }.newInstance()
    } catch (error: Throwable) {
        fail("Missing per-recording cancellation epoch (${error.javaClass.simpleName})")
    }

    private fun method(target: Any, name: String): java.lang.reflect.Method =
        target.javaClass.methods.firstOrNull {
            it.name == name || it.name.startsWith(name + "$")
        } ?: throw AssertionError("Missing cancellation epoch method: $name")

    private fun begin(target: Any): Long {
        val found = method(target, "beginRecording")
        found.isAccessible = true
        return found.invoke(target) as Long
    }

    private fun cancel(target: Any) {
        val found = method(target, "cancelActive")
        found.isAccessible = true
        found.invoke(target)
    }

    private fun isCancelled(target: Any, token: Long): Boolean {
        val found = method(target, "isCancelled")
        found.isAccessible = true
        return found.invoke(target, token) as Boolean
    }

    @Test
    fun stopStaysTerminalForItsOwnRecordingAfterANewStart() {
        val epoch = epochInstance()
        val first = begin(epoch)
        cancel(epoch) // STOP for the first recording
        val second = begin(epoch) // new accepted START resets the old shared flag
        assertTrue("The STOP must remain terminal for the recording it stopped", isCancelled(epoch, first))
        assertFalse("The new recording must not inherit the previous STOP", isCancelled(epoch, second))
    }

    @Test
    fun anActiveRecordingIsCancelledByItsOwnStop() {
        val epoch = epochInstance()
        val token = begin(epoch)
        assertFalse("An accepted recording starts uncancelled", isCancelled(epoch, token))
        cancel(epoch)
        assertTrue("STOP marks the current recording", isCancelled(epoch, token))
    }

    @Test
    fun aRecordingThatNeverReceivedAStopIsNeverCancelled() {
        val epoch = epochInstance()
        val first = begin(epoch)
        val second = begin(epoch)
        assertFalse(isCancelled(epoch, first))
        assertFalse(isCancelled(epoch, second))
    }

    @Test
    fun anIdleStopDoesNotCancelAFutureRecording() {
        val epoch = epochInstance()
        cancel(epoch) // STOP before any START was admitted
        val token = begin(epoch)
        assertFalse("A later START must get a clean cancellation epoch", isCancelled(epoch, token))
    }
}

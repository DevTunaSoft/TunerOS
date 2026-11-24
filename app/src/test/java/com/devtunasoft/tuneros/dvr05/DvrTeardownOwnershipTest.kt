package com.devtunasoft.tuneros.dvr05

import com.devtunasoft.tuneros.DvrServiceTeardownGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DVR-05 service-teardown ownership regressions.
 *
 * The capture worker clears its active id and then removes the foreground notification and
 * stops the service. A START accepted in that window owns the notification now; the finishing
 * worker must not strip it or stop the service under the new recording. These tests exercise
 * the pure gate seam used by that decision. Only synthetic integer start ids are used: no
 * URLs, credentials, media or android.* stubs.
 */
class DvrTeardownOwnershipTest {

    @Test
    fun finishingCaptureStopsServiceWhenItStillOwnsTeardown() {
        val gate = DvrServiceTeardownGate()
        gate.beginStart(7)
        assertTrue("The only accepted capture owns teardown", gate.releaseIfOwner(7))
        assertFalse("Teardown must be claimed at most once", gate.releaseIfOwner(7))
    }

    @Test
    fun newStartInFinalizationWindowMustNotBeStoppedByOldCapture() {
        val gate = DvrServiceTeardownGate()
        gate.beginStart(11) // recording A accepted
        gate.beginStart(12) // recording B accepted while A is still unwinding
        assertFalse(
            "A must not strip B's foreground notification or stop B's service",
            gate.releaseIfOwner(11),
        )
        assertTrue("B still owns teardown", gate.releaseIfOwner(12))
        assertFalse("B's teardown is claimed once", gate.releaseIfOwner(12))
    }

    @Test
    fun rejectedStartReleasesItsClaimWithoutStrandingTheOldCapture() {
        val gate = DvrServiceTeardownGate()
        gate.beginStart(21)
        gate.beginStart(22) // B claims, then is refused by the catalog/foreground/SCHEDULED check.
        assertTrue("B releases its own claim", gate.releaseIfOwner(22))
        assertFalse(
            "After B releases, A's stale claim must not tear down the service either",
            gate.releaseIfOwner(21),
        )
        gate.beginStart(23)
        assertTrue("Gate is reusable for a later accepted capture", gate.releaseIfOwner(23))
    }

    @Test
    fun stopCommandDoesNotTransferTeardownOwnership() {
        val gate = DvrServiceTeardownGate()
        gate.beginStart(31)
        // A STOP command does not call beginStart: it sets cancelled and defers teardown to
        // the capture worker so SQLite finalization completes before the catalog is closed.
        assertTrue("Capture still stops the service after a STOP", gate.releaseIfOwner(31))
    }

    @Test
    fun concurrentReleaseGrantsTeardownToExactlyOneCaller() {
        val gate = DvrServiceTeardownGate()
        gate.beginStart(41)
        val winners = java.util.concurrent.atomic.AtomicInteger(0)
        val contenders = (0 until 16).map {
            Thread {
                repeat(200) { if (gate.releaseIfOwner(41)) winners.incrementAndGet() }
            }
        }
        contenders.forEach { it.start() }
        contenders.forEach { it.join(5_000) }
        assertEquals("Only one caller may win the single teardown claim", 1, winners.get())
    }
}

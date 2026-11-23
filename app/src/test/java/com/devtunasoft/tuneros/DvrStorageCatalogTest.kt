package com.devtunasoft.tuneros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DVR-02: the ONLY catalog transition a storage fault is allowed to produce.
 *
 * A fault must never be recorded as COMPLETED, and must never expose a completed storage
 * location. Synthetic state only: no catalog row, URL or payload is read or written here.
 */
class DvrStorageCatalogTest {

    @Test
    fun diskFullMidWriteIsPartialAndNeverCompleted() {
        val decision = StorageCatalogPolicy.decide(StorageFault(StorageFaultKind.DISK_FULL), 512L, false)
        assertEquals("PARTIAL", decision.state)
        assertNotEquals("COMPLETED", decision.state)
        assertNull("A fault must never supply a completed location", decision.completedLocation)
        assertNotNull(decision.fault)
        assertEquals(StorageFaultKind.DISK_FULL, decision.fault!!.kind)
    }

    @Test
    fun permissionLossBeforeAnyByteIsFailedAndDiscarded() {
        val decision = StorageCatalogPolicy.decide(StorageFault(StorageFaultKind.PERMISSION_LOST), 0L, false)
        assertEquals("FAILED", decision.state)
        assertTrue("An empty partial left by a permission loss must be deleted", decision.discardFile)
        assertNull(decision.completedLocation)
    }

    @Test
    fun providerDisconnectWithBytesNeverCompletes() {
        val decision = StorageCatalogPolicy.decide(StorageFault(StorageFaultKind.PROVIDER_DISCONNECTED), 4096L, false)
        assertEquals("PARTIAL", decision.state)
        assertNull(decision.completedLocation)
    }

    @Test
    fun cancellationDuringPartialWriteDiscardsTheFileAndNeverCompletes() {
        val decision = StorageCatalogPolicy.decide(
            StorageFault(StorageFaultKind.PROVIDER_DISCONNECTED), 300L, true,
        )
        assertEquals("CANCELLED", decision.state)
        assertNotEquals("COMPLETED", decision.state)
        assertTrue("Cancellation must delete the partial so no orphan remains", decision.discardFile)
        assertEquals(StorageFaultKind.CANCELLED, decision.fault!!.kind)
        assertNull(decision.completedLocation)
    }

    @Test
    fun cancellationWithAnExplicitCancelFaultStaysCancelled() {
        val decision = StorageCatalogPolicy.decide(StorageFault(StorageFaultKind.CANCELLED), 1L, false)
        assertEquals("CANCELLED", decision.state)
        assertTrue(decision.discardFile)
    }

    @Test
    fun onlyAnUnfaultedNonEmptyCaptureMayBeCompleted() {
        assertEquals("COMPLETED", StorageCatalogPolicy.decide(null, 1L, false).state)
        assertEquals("FAILED", StorageCatalogPolicy.decide(null, 0L, false).state)
        assertEquals("CANCELLED", StorageCatalogPolicy.decide(null, 1L, true).state)
    }

    @Test
    fun finalizationFailureIsTypedAndNeverCompleted() {
        val decision = StorageCatalogPolicy.decide(
            StorageFault(StorageFaultKind.FINALIZATION_FAILED), 256L, false,
        )
        assertEquals("PARTIAL", decision.state)
        assertNull(decision.completedLocation)
        assertEquals("FINALIZATION_FAILED", decision.fault!!.code)
    }

    @Test
    fun faultCodesAreStablePayloadFreeDiagnostics() {
        assertEquals("DISK_FULL", StorageFault(StorageFaultKind.DISK_FULL).code)
        assertEquals("PERMISSION_LOST", StorageFault(StorageFaultKind.PERMISSION_LOST).code)
        assertEquals("PROVIDER_DISCONNECTED", StorageFault(StorageFaultKind.PROVIDER_DISCONNECTED).code)
        assertEquals("CANCELLED", StorageFault(StorageFaultKind.CANCELLED).code)
    }
}

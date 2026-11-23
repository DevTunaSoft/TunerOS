package com.devtunasoft.tuneros

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * DVR-02 JVM fault-injection tests for the SAF/storage decision layer.
 *
 * These tests model permission loss, disk-full/quota, provider disconnect and cancellation
 * during a partial write. They use only synthetic ids and in-memory scratch streams: no provider
 * URL, credential, DRM key, feed URL or recording payload is read, written or logged.
 */
class DvrStorageFaultPolicyTest {

    @Test
    fun permissionLossProducesTypedFaultWithoutLocation() {
        val result = StorageFaultPolicy.attempt({ false }) { throw SecurityException("denied") }
        assertTrue("A permission loss must be a structured failure", result is StorageResult.Failure)
        assertEquals(StorageFaultKind.PERMISSION_LOST, result.faultOrNull()!!.kind)
        assertNull("A fault must never hand back a destination", result.valueOrNull())
    }

    @Test
    fun noSpaceAndQuotaAreBothTypedDiskFull() {
        for (message in listOf("write failed: ENOSPC", "Quota exceeded", "No space left on device")) {
            val fault = StorageFaultPolicy.classify(IOException(message), false)
            assertEquals("Expected DISK_FULL for: $message", StorageFaultKind.DISK_FULL, fault.kind)
        }
    }

    @Test
    fun providerDisconnectProducesTypedFault() {
        val fault = StorageFaultPolicy.classify(IOException("DeadObjectException"), false)
        assertEquals(StorageFaultKind.PROVIDER_DISCONNECTED, fault.kind)
    }

    @Test
    fun typedAccessExceptionSurvivesClassification() {
        val injected = StorageAccessException(StorageFaultKind.PROVIDER_DISCONNECTED)
        assertEquals(
            StorageFaultKind.PROVIDER_DISCONNECTED,
            StorageFaultPolicy.classify(injected, false).kind,
        )
    }

    @Test
    fun cancellationWinsOverAnOverlappingDiskFullFault() {
        val fault = StorageFaultPolicy.classify(IOException("No space left on device"), true)
        assertEquals("Cancellation is the authoritative terminal reason", StorageFaultKind.CANCELLED, fault.kind)
    }

    @Test
    fun exceptionTextNeverLeaksIntoTheTypedFault() {
        val secret = "content://provider/tree/token=TOPSECRET"
        val fault = StorageFaultPolicy.classify(IOException(secret), false)
        assertEquals(StorageFaultKind.PROVIDER_DISCONNECTED, fault.kind)
        assertEquals("PROVIDER_DISCONNECTED", fault.code)
        assertFalse("A fault must not carry raw exception text", fault.toString().contains("TOPSECRET"))
    }

    @Test
    fun aFailedStepIsNeverRetried() {
        var calls = 0
        val result = StorageFaultPolicy.attempt({ false }) {
            calls++
            throw IOException("boom")
        }
        assertEquals("Fault handling must be bounded: one attempt, no retry loop", 1, calls)
        assertTrue(result is StorageResult.Failure)
    }

    @Test
    fun selectedSafProviderNeverSilentlyFallsBackOnDisconnect() {
        val result = RecordingSinkPolicy.selectDestination(
            safTreeSelected = true,
            safAvailableBytes = null,
            internalAvailableBytes = 10L * 1024 * 1024 * 1024,
            minFreeBytes = 1L,
        )
        assertTrue(result is StorageResult.Failure)
        assertEquals(StorageFaultKind.PROVIDER_DISCONNECTED, result.faultOrNull()!!.kind)
        assertNull("No alternate destination may be returned on fault", result.valueOrNull())
    }

    @Test
    fun selectedSafProviderNeverFallsBackWhenQuotaIsExceeded() {
        val result = RecordingSinkPolicy.selectDestination(
            safTreeSelected = true,
            safAvailableBytes = 8L,
            internalAvailableBytes = 10L * 1024 * 1024 * 1024,
            minFreeBytes = 1024L,
        )
        assertTrue(result is StorageResult.Failure)
        assertEquals(StorageFaultKind.DISK_FULL, result.faultOrNull()!!.kind)
        assertNull(result.valueOrNull())
    }

    @Test
    fun internalStorageIsUsedOnlyWhenNoProviderWasSelected() {
        val result = RecordingSinkPolicy.selectDestination(
            safTreeSelected = false,
            safAvailableBytes = null,
            internalAvailableBytes = 100L,
            minFreeBytes = 1L,
        )
        assertEquals(StorageDestination.INTERNAL, result.valueOrNull())
        assertNull(result.faultOrNull())
    }

    @Test
    fun fullInternalStorageIsRejectedWithoutRedirectingToSaf() {
        val result = RecordingSinkPolicy.selectDestination(
            safTreeSelected = false,
            safAvailableBytes = 10L * 1024 * 1024 * 1024,
            internalAvailableBytes = 0L,
            minFreeBytes = 1L,
        )
        assertTrue(result is StorageResult.Failure)
        assertEquals(StorageFaultKind.DISK_FULL, result.faultOrNull()!!.kind)
    }

    @Test
    fun healthySafProviderIsSelected() {
        val result = RecordingSinkPolicy.selectDestination(
            safTreeSelected = true,
            safAvailableBytes = 100L,
            internalAvailableBytes = 0L,
            minFreeBytes = 1L,
        )
        assertEquals(StorageDestination.SAF, result.valueOrNull())
    }

    @Test
    fun boundedStreamStopsExactlyAtItsLimitAndReportsDiskFull() {
        val delegate = ByteArrayOutputStream()
        val guarded = BoundedOutputStream(delegate, 4)
        guarded.write(byteArrayOf(1, 2, 3, 4))
        assertEquals(4L, guarded.count)
        try {
            guarded.write(5)
            fail("A write past the storage bound must be refused")
        } catch (expected: StorageAccessException) {
            assertEquals(StorageFaultKind.DISK_FULL, expected.faultKind)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), delegate.toByteArray())
        guarded.close()
    }

    @Test
    fun oversizedBulkWriteIsRejectedAtomically() {
        val delegate = ByteArrayOutputStream()
        val guarded = BoundedOutputStream(delegate, 2)
        try {
            guarded.write(byteArrayOf(1, 2, 3), 0, 3)
            fail("An over-budget bulk write must not be partially applied")
        } catch (expected: StorageAccessException) {
            assertEquals(StorageFaultKind.DISK_FULL, expected.faultKind)
        }
        assertEquals("Delegate must see no bytes from a rejected bulk write", 0, delegate.size())
        assertEquals(0L, guarded.count)
        guarded.close()
    }

    @Test
    fun boundedStreamUsesFixedMemoryAndExactCounting() {
        val delegate = ByteArrayOutputStream()
        val guarded = BoundedOutputStream(delegate, Long.MAX_VALUE)
        val chunk = ByteArray(1024) { 7 }
        repeat(64) { guarded.write(chunk) }
        assertEquals(64L * 1024, guarded.count)
        assertEquals(64 * 1024, delegate.size())
        guarded.close()
    }
}

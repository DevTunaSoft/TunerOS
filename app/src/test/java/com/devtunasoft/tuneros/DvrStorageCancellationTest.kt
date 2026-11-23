package com.devtunasoft.tuneros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * DVR-02: file-level fault/cancellation checks on scratch directories only.
 *
 * Every fixture uses a fresh JVM temp directory and synthetic ids. No real recording, provider
 * tree, feed URL or credential is touched.
 */
class DvrStorageCancellationTest {
    private fun scratch(): File = Files.createTempDirectory("dvr02-storage-").toFile()

    private fun cleanup(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) cleanup(child)
            child.delete()
        }
        dir.delete()
    }

    @Test
    fun cancelledPartialWriteLeavesNoOrphanFile() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "cancel-partial", ".ts")
            sink.openOutput().use { it.write(byteArrayOf(1, 2, 3)) }
            assertEquals(3L, sink.bytesWritten)

            val fault = StorageFaultPolicy.classify(IOException("interrupted"), true)
            val decision = StorageCatalogPolicy.decide(fault, sink.bytesWritten, true)
            assertEquals("CANCELLED", decision.state)
            assertTrue(decision.discardFile)
            assertTrue(sink.delete())

            assertFalse(File(dir, "cancel-partial.ts").exists())
            assertFalse(File(dir, "cancel-partial.partial").exists())
            assertEquals("No orphan may remain after cancellation", 0, dir.listFiles()!!.size)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun cancellationCannotPromoteAPartialFileToCompleted() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "cancel-no-promote", ".ts")
            sink.openOutput().use { it.write(byteArrayOf(9, 8, 7, 6)) }
            val decision = StorageCatalogPolicy.decide(
                StorageFault(StorageFaultKind.CANCELLED), sink.bytesWritten, true,
            )
            // Cancellation is terminal: the service does not finalize in this state.
            assertFalse("Cancelled capture must never be COMPLETED", decision.state == "COMPLETED")
            assertFalse(decision.finalize)
            assertFalse(File(dir, "cancel-no-promote.ts").exists())
            assertEquals(4L, File(dir, "cancel-no-promote.partial").length())
            assertTrue(sink.delete())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun diskFullGuardPreventsACompletedFileAfterABoundedWrite() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "diskfull", ".ts")
            val guarded = BoundedOutputStream(sink.openOutput(), 4)
            guarded.write(byteArrayOf(1, 2, 3, 4))
            var fault: StorageFault? = null
            try {
                guarded.write(99)
                fail("The storage bound must reject the next byte")
            } catch (expected: StorageAccessException) {
                fault = StorageFault(expected.faultKind)
            }
            guarded.close()
            assertNotNull(fault)
            assertEquals(StorageFaultKind.DISK_FULL, fault!!.kind)
            assertEquals(4L, sink.bytesWritten)

            val decision = StorageCatalogPolicy.decide(fault, sink.bytesWritten, false)
            assertEquals("PARTIAL", decision.state)
            assertFalse(decision.finalize)
            assertFalse(File(dir, "diskfull.ts").exists())
            assertEquals("Only the bounded bytes were persisted", 4L, File(dir, "diskfull.partial").length())
            assertTrue(sink.delete())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun emptyPartialFromPermissionLossIsDeletedNotCataloguedAsCompleted() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "empty-perm", ".ts")
            sink.openOutput().use { }
            assertEquals(0L, sink.bytesWritten)

            val decision = StorageCatalogPolicy.decide(
                StorageFault(StorageFaultKind.PERMISSION_LOST), 0L, false,
            )
            assertEquals("FAILED", decision.state)
            assertFalse(decision.finalize)
            assertTrue(decision.discardFile)
            assertTrue(sink.delete())
            assertFalse(File(dir, "empty-perm.partial").exists())
            assertFalse(File(dir, "empty-perm.ts").exists())
        } finally {
            cleanup(dir)
        }
    }
}

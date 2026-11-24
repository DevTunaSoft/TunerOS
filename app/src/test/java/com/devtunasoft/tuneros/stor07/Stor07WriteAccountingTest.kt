package com.devtunasoft.tuneros.stor07

import com.devtunasoft.tuneros.InternalRecordingSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * STOR-07: deterministic write-accounting regressions for the app recording sink.
 *
 * Every fixture uses a fresh JVM temp directory and synthetic ids only. No provider URL,
 * credential, DRM key or recording media is read, written or logged. These seams (exact
 * byte accounting for bulk/single/offset writes, post-close refusal, and on-disk reconciliation
 * after finalization) are not exercised by A4 DVR-01/02, C16 SAF-06, C2 DVR-03 or C13 RESTORE-02.
 */
class Stor07WriteAccountingTest {

    private fun scratch(): File = Files.createTempDirectory("stor07-write-").toFile()

    private fun cleanup(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) cleanup(child)
            child.delete()
        }
        dir.delete()
    }

    @Test
    fun bulkWriteCountsExactlyTheSuppliedOffsetAndLength() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "bulk-offset", ".ts")
            sink.openOutput().use { output ->
                output.write(byteArrayOf(90, 91, 11, 22, 33, 44, 92), 2, 4)
            }
            assertEquals(4L, sink.bytesWritten)
            assertEquals(
                "Only the requested slice may reach the partial file",
                "0B,16,21,2C",
                File(dir, "bulk-offset.partial").readBytes().joinToString(",") { "%02X".format(it) },
            )
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun mixedSingleAndBulkWritesAccumulateTheExactTotal() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "mixed-writes", ".ts")
            sink.openOutput().use { output ->
                output.write(7)
                output.write(byteArrayOf(1, 2, 3))
                output.write(8)
                output.write(byteArrayOf(4, 5), 1, 1)
            }
            assertEquals(
                "1 + 3 + 1 + 1 bytes were accepted by the delegate",
                6L,
                sink.bytesWritten,
            )
            assertEquals(6L, File(dir, "mixed-writes.partial").length())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun zeroLengthWriteDoesNotChangeTheCount() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "zero-length", ".ts")
            sink.openOutput().use { output ->
                output.write(byteArrayOf(1, 2))
                output.write(byteArrayOf(9, 9, 9), 1, 0)
            }
            assertEquals(2L, sink.bytesWritten)
            assertEquals(2L, File(dir, "zero-length.partial").length())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun writeAfterCloseIsRefusedAndDoesNotInflateTheCount() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "after-close", ".ts")
            val output = sink.openOutput()
            output.write(byteArrayOf(1, 2, 3))
            output.close()

            try {
                output.write(byteArrayOf(7, 7))
                fail("A closed recording stream must refuse further writes")
            } catch (expected: IOException) {
                // Expected: FileOutputStream rejects writes after close.
            }
            assertEquals("A refused write must not be counted", 3L, sink.bytesWritten)
            assertEquals(3L, File(dir, "after-close.partial").length())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun bytesWrittenStillMatchesThePersistedFileAfterFinalization() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "post-finalize", ".ts")
            sink.openOutput().use { it.write(byteArrayOf(3, 1, 4, 1, 5)) }
            assertEquals(5L, sink.bytesWritten)

            val finalized = sink.finalizeRecording()
            assertTrue("A non-empty partial must finalize", finalized != null)
            assertEquals("The count must survive the rename", 5L, sink.bytesWritten)
            assertEquals(
                "The completed file length is the authoritative persisted size",
                File(dir, "post-finalize.ts").length(),
                sink.bytesWritten,
            )
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun aSinkThatNeverOpenedOutputOwnsNoBytesAndDeletesNothing() {
        val dir = scratch()
        try {
            val neighbour = File(dir, "other.partial")
            neighbour.writeBytes(byteArrayOf(5))
            val sink = InternalRecordingSink(dir, "not-opened", ".ts")

            assertEquals(0L, sink.bytesWritten)
            assertFalse("No output was opened, so the sink owns no file", sink.delete())
            assertTrue(neighbour.exists())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun finalizeFailsClosedWhenThePartialVanishedBeforeTheRename() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "vanished", ".ts")
            sink.openOutput().use { it.write(byteArrayOf(6, 7)) }
            assertTrue(File(dir, "vanished.partial").delete())

            val finalized = sink.finalizeRecording()
            assertTrue(
                "A missing partial must never be reported as a completed recording",
                finalized == null,
            )
            assertFalse(File(dir, "vanished.ts").exists())
        } finally {
            cleanup(dir)
        }
    }
}

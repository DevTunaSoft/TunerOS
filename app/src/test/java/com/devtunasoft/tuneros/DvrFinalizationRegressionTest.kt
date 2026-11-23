package com.devtunasoft.tuneros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Bounded, deterministic partial-file fault injection for the app-layer DVR finalization path.
 *
 * Every fixture uses a fresh JVM temp directory and synthetic ids only: no provider URL,
 * credential, DRM key or recording payload is read, written or logged.
 */
class DvrFinalizationRegressionTest {
    private fun scratch(): File = Files.createTempDirectory("dvr01-finalize-").toFile()

    private fun cleanup(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) cleanup(child)
            child.delete()
        }
        dir.delete()
    }

    @Test
    fun zeroBytePartialIsNeverPromotedToACompletedFile() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "zero-byte", ".ts")
            // Fault injection: the engine reports a clean stop but the output stream received nothing.
            sink.openOutput().use { }
            assertEquals(0L, sink.bytesWritten)

            assertNull(
                "An empty partial must never be renamed to a completed recording",
                sink.finalizeRecording(),
            )
            assertFalse(File(dir, "zero-byte.ts").exists())
            assertTrue(File(dir, "zero-byte.partial").exists())
            assertTrue(sink.delete())
            assertFalse(File(dir, "zero-byte.partial").exists())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun doubleFinalizationCannotCorruptACompletedRecording() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "double-finalize", ".ts")
            sink.openOutput().use { it.write(byteArrayOf(11, 22, 33)) }
            val first = sink.finalizeRecording()
            assertTrue(first != null)

            val second = sink.finalizeRecording()
            assertEquals(
                "A second finalization must be a no-op, not a failure that demotes COMPLETED to PARTIAL",
                first,
                second,
            )
            val completed = File(dir, "double-finalize.ts")
            assertEquals(3L, completed.length())
            completed.inputStream().use { input ->
                assertEquals(11, input.read())
                assertEquals(22, input.read())
                assertEquals(33, input.read())
                assertEquals(-1, input.read())
            }
            assertTrue(sink.delete())
            assertFalse(completed.exists())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun outputCanOnlyBeOpenedOncePerSink() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "single-open", ".ts")
            sink.openOutput().use { it.write(7) }
            try {
                sink.openOutput().use { }
                fail("A second open must be refused so one capture cannot overwrite its own output")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("already opened"))
            }
            assertEquals(1L, sink.bytesWritten)
            assertTrue(sink.delete())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun aSecondSinkCannotClaimAnExistingPartialOrCompletedId() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "collision", ".ts")
            sink.openOutput().use { it.write(9) }
            assertFalse(
                runCatching { InternalRecordingSink(dir, "collision", ".ts").openOutput().use { } }.isSuccess,
            )
            assertTrue(sink.finalizeRecording() != null)
            assertFalse(
                runCatching { InternalRecordingSink(dir, "collision", ".ts").openOutput().use { } }.isSuccess,
            )
            assertTrue(sink.delete())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun renameCollisionKeepsThePartialFileAndReportsFailure() {
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "rename-collision", ".ts")
            sink.openOutput().use { it.write(byteArrayOf(1, 2)) }
            val occupant = File(dir, "rename-collision.ts")
            occupant.writeBytes(byteArrayOf(99))

            assertNull(sink.finalizeRecording())
            assertEquals(1L, occupant.length())
            assertEquals(2L, File(dir, "rename-collision.partial").length())
            assertTrue(sink.delete())
            assertTrue(occupant.delete())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun deleteNeverRemovesAFileTheSinkDoesNotOwn() {
        val dir = scratch()
        try {
            val neighbour = File(dir, "other.partial")
            neighbour.writeBytes(byteArrayOf(5))
            val sink = InternalRecordingSink(dir, "owned", ".ts")
            assertFalse("A sink that owns nothing must not delete anything", sink.delete())
            assertTrue(neighbour.exists())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun cancelledOutcomeNeverRenamesThePartialFile() {
        // Models applyOutcome: the engine says COMPLETED, but STOP arrived before the decision.
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "stop-after-copy", ".ts")
            sink.openOutput().use { it.write(byteArrayOf(1, 2, 3)) }
            val decision = RecordingOutcomePolicy.settle(
                RecordingOutcome("COMPLETED", 3L, true), cancelled = true, sink.bytesWritten,
            )
            if (decision.finalize) sink.finalizeRecording()

            assertEquals("CANCELLED", decision.state)
            assertFalse(File(dir, "stop-after-copy.ts").exists())
            assertEquals(3L, File(dir, "stop-after-copy.partial").length())
            assertTrue(sink.delete())
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun emptyCompletedOutcomeNeverLeavesACompletedFile() {
        // Models applyOutcome for a zero-byte stop: FAILED, delete the empty partial.
        val dir = scratch()
        try {
            val sink = InternalRecordingSink(dir, "empty-stop", ".ts")
            sink.openOutput().use { }
            val decision = RecordingOutcomePolicy.settle(
                RecordingOutcome("COMPLETED", 0L, true), cancelled = false, sink.bytesWritten,
            )
            if (decision.finalize) sink.finalizeRecording()
            if (decision.discardEmpty) sink.delete()

            assertEquals("FAILED", decision.state)
            assertFalse(File(dir, "empty-stop.ts").exists())
            assertFalse(File(dir, "empty-stop.partial").exists())
        } finally {
            cleanup(dir)
        }
    }
}

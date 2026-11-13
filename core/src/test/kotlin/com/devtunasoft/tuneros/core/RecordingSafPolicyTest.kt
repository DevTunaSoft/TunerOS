package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** No Android provider, files or extra worker are required for this fail-safe policy. */
class RecordingSafPolicyTest {
    @Test fun rootMatchingRequiresDocumentIdBoundaries() {
        assertTrue(RecordingPolicy.isDocumentOnRoot("primary:Movies/News", "primary:"))
        assertTrue(RecordingPolicy.isDocumentOnRoot("primary:Movies", "primary"))
        assertTrue(RecordingPolicy.isDocumentOnRoot("ABCD-1234:", "ABCD-1234"))
        assertTrue(RecordingPolicy.isDocumentOnRoot("ABCD-1234:Shows/News", "ABCD-1234:"))
        assertTrue(RecordingPolicy.isDocumentOnRoot("primary:Shows", "primary:Shows"))
        assertTrue(RecordingPolicy.isDocumentOnRoot("primary:Shows/2026", "primary:Shows"))

        assertFalse(RecordingPolicy.isDocumentOnRoot("primary2:Movies", "primary"))
        assertFalse(RecordingPolicy.isDocumentOnRoot("ABCD-12345:Shows", "ABCD-1234"))
        assertFalse(RecordingPolicy.isDocumentOnRoot("primary:ShowsMore", "primary:Shows"))
        assertFalse(RecordingPolicy.isDocumentOnRoot("primary:Shows", ""))
        assertFalse(RecordingPolicy.isDocumentOnRoot("primary:Shows", "missing:"))
    }

    @Test fun missingOrInvalidCapacityStopsInsteadOfReusingStaleSpace() {
        assertEquals(0L, RecordingPolicy.remainingFreeBytes(null, 0))
        assertEquals(0L, RecordingPolicy.remainingFreeBytes(null, 20L * 1024 * 1024))
        assertEquals(0L, RecordingPolicy.remainingFreeBytes(-1L, 0))
        assertEquals(0L, RecordingPolicy.remainingFreeBytes(0, 0))
        assertEquals(384L, RecordingPolicy.remainingFreeBytes(512L, 128L))
        assertEquals(0L, RecordingPolicy.remainingFreeBytes(512L, 512L))
        assertEquals(0L, RecordingPolicy.remainingFreeBytes(512L, 513L))
        assertEquals(Long.MAX_VALUE, RecordingPolicy.remainingFreeBytes(Long.MAX_VALUE, 0L))
        assertEquals(Long.MAX_VALUE - 1, RecordingPolicy.remainingFreeBytes(Long.MAX_VALUE, 1L))
        assertFailsWith<IllegalArgumentException> {
            RecordingPolicy.remainingFreeBytes(100L, -1L)
        }
    }
}

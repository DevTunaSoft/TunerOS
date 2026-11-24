package com.devtunasoft.tuneros.saf06

import android.content.Context
import android.net.Uri
import com.devtunasoft.tuneros.RecordingDestinationStore
import com.devtunasoft.tuneros.RecordingLocationAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** SAF-06 negative regressions for the two audited-clean owned files. Synthetic fixtures only. */
class Saf06DestinationAndLocationTest {

    @Test
    fun destinationStoreRoundTripsAValidTreeAndClearsIt() {
        val context = Context()
        val store = RecordingDestinationStore(context)
        assertTrue(store.saveTreeUri(Saf06Fixture.treeUri()))
        assertEquals(Saf06Fixture.treeUri(), store.selectedTreeUri())
        assertTrue(store.clear())
        assertNull(store.selectedTreeUri())
    }

    @Test
    fun destinationStoreRejectsNonContentAndBlankAuthorityValuesOnRead() {
        val context = Context()
        val prefs = context.getSharedPreferences("tuneros_recording_storage", Context.MODE_PRIVATE)
        val store = RecordingDestinationStore(context)

        assertTrue(store.saveTreeUri(Uri.parse("file:///synthetic/saf06/not-a-tree")))
        assertNull("file scheme must never become a SAF destination", store.selectedTreeUri())

        assertTrue(store.saveTreeUri(Uri.parse("content:///tree/primary%3ASyntheticFolder")))
        assertNull("blank authority must never become a SAF destination", store.selectedTreeUri())

        assertTrue(
            prefs.edit().putString("recording_tree_uri", "synthetic-corrupt-value").commit(),
        )
        assertNull("an unparseable persisted value must be ignored", store.selectedTreeUri())
    }

    @Test
    fun playableContentUriRequiresPersistedReadAccessAndAnExistingDocument() {
        val uri = Saf06Fixture.recordingUri().toString()
        val readable = RecordingLocationAccess.playableUri(
            Saf06Fixture.contentContext(
                persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER, write = false)),
            ),
            uri,
        )
        assertEquals(Saf06Fixture.recordingUri(), readable)

        assertNull(
            "no grant means no playable SAF document",
            RecordingLocationAccess.playableUri(Saf06Fixture.contentContext(), uri),
        )
        assertNull(
            "a missing document is not playable",
            RecordingLocationAccess.playableUri(
                Saf06Fixture.contentContext(
                    persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
                    docState = Saf06Fixture.DocState.MISSING,
                ),
                uri,
            ),
        )
        assertNull(
            "an unreachable provider fails closed",
            RecordingLocationAccess.playableUri(
                Saf06Fixture.contentContext(
                    persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
                    docState = Saf06Fixture.DocState.UNAVAILABLE,
                ),
                uri,
            ),
        )
    }

    @Test
    fun deleteContentUriRequiresPersistedWriteAccess() {
        val uri = Saf06Fixture.recordingUri().toString()
        val deleted = ArrayList<Uri>()
        val writable = Saf06Fixture.contentContext(
            persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
            deleted = deleted,
        )
        assertEquals(
            RecordingLocationAccess.DeleteResult.DELETED,
            RecordingLocationAccess.delete(writable, uri),
        )
        assertEquals(listOf(Saf06Fixture.recordingUri()), deleted)

        assertEquals(
            RecordingLocationAccess.DeleteResult.INVALID,
            RecordingLocationAccess.delete(
                Saf06Fixture.contentContext(
                    persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER, write = false)),
                ),
                uri,
            ),
        )
        assertEquals(
            RecordingLocationAccess.DeleteResult.INVALID,
            RecordingLocationAccess.delete(Saf06Fixture.contentContext(), uri),
        )
        assertEquals(
            RecordingLocationAccess.DeleteResult.MISSING,
            RecordingLocationAccess.delete(
                Saf06Fixture.contentContext(
                    persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
                    docState = Saf06Fixture.DocState.MISSING,
                ),
                uri,
            ),
        )
        assertEquals(
            RecordingLocationAccess.DeleteResult.UNAVAILABLE,
            RecordingLocationAccess.delete(
                Saf06Fixture.contentContext(
                    persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
                    docState = Saf06Fixture.DocState.UNAVAILABLE,
                ),
                uri,
            ),
        )
    }

    @Test
    fun internalPathsOutsideTheRecordingsRootAreRejected() {
        val parent = Files.createTempDirectory("saf06-").toFile()
        try {
            val recordings = File(parent, "recordings").apply { mkdirs() }
            val inside = File(recordings, "synthetic-inside.ts").apply { createNewFile() }
            val outside = File(parent, "synthetic-outside.ts").apply { createNewFile() }
            val context = object : Context() {
                override fun getExternalFilesDir(type: String?): File? = recordings
                override val filesDir: File get() = parent
            }

            assertEquals(
                RecordingLocationAccess.DeleteResult.DELETED,
                RecordingLocationAccess.delete(context, inside.absolutePath),
            )
            assertFalse("inside scratch file should be gone", inside.exists())
            assertEquals(
                "a sibling path must not be reachable through the recordings root",
                RecordingLocationAccess.DeleteResult.INVALID,
                RecordingLocationAccess.delete(context, outside.absolutePath),
            )
            assertTrue("outside scratch file must survive", outside.exists())
            assertNull(
                "a file-scheme pointer outside the root is not playable",
                RecordingLocationAccess.playableUri(context, Uri.fromFile(outside).toString()),
            )
        } finally {
            parent.deleteRecursively()
        }
    }
}

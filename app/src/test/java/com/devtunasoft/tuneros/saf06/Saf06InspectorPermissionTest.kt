package com.devtunasoft.tuneros.saf06

import com.devtunasoft.tuneros.SafStorageInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SAF-06 fail-open regression. A storage destination is only usable while the app still holds a
 * persisted, writable grant for that exact tree. Every id is synthetic; no real file, URL or
 * credential is involved.
 */
class Saf06InspectorPermissionTest {
    @Test
    fun revokedPersistedGrant_isReportedUnavailable() {
        // The stored tree is still syntactically valid and the provider still advertises create,
        // but the app no longer holds any persisted grant. Inspection must fail closed.
        val context = Saf06Fixture.context(persisted = emptyList())
        assertNull(
            "revoked SAF grant must not be reported as a usable destination",
            SafStorageInspector.inspect(context, Saf06Fixture.treeUri()),
        )
    }

    @Test
    fun readOnlyPersistedGrant_isReportedUnavailable() {
        val context = Saf06Fixture.context(
            persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER, read = true, write = false)),
        )
        assertNull(
            "a read-only grant cannot support recording writes",
            SafStorageInspector.inspect(context, Saf06Fixture.treeUri()),
        )
    }

    @Test
    fun grantForADifferentTree_isReportedUnavailable() {
        val context = Saf06Fixture.context(
            persisted = listOf(Saf06Fixture.permission(Saf06Fixture.OTHER_FOLDER)),
        )
        assertNull(
            "a grant for another tree must not authorize this destination",
            SafStorageInspector.inspect(context, Saf06Fixture.treeUri()),
        )
    }

    @Test
    fun readWriteGrantForTheSelectedTree_isInspected() {
        val context = Saf06Fixture.context(
            persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
        )
        val info = SafStorageInspector.inspect(context, Saf06Fixture.treeUri())
        assertNotNull("a live writable grant must remain usable", info)
        assertEquals(Saf06Fixture.treeUri(), info!!.treeUri)
        assertEquals("primary", info.rootTitle)
        assertEquals(Saf06Fixture.USABLE_BYTES, info.availableBytes)
    }

    @Test
    fun nonWritableDirectory_isReportedUnavailable() {
        val context = Saf06Fixture.context(
            persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
            createAllowed = false,
        )
        assertNull(
            "provider directory without create support stays unavailable",
            SafStorageInspector.inspect(context, Saf06Fixture.treeUri()),
        )
    }

    @Test
    fun foreignAuthority_isRejected() {
        val foreign = android.net.Uri.parse(
            "content://com.example.synthetic.provider/tree/primary%3A${Saf06Fixture.FOLDER}",
        )
        val context = Saf06Fixture.context(
            persisted = listOf(Saf06Fixture.permission(Saf06Fixture.FOLDER)),
        )
        assertNull(
            "only the external-storage provider is an accepted destination",
            SafStorageInspector.inspect(context, foreign),
        )
    }
}

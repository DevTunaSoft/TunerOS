package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deterministic regression coverage for backup version handling, truncated or corrupt input, bounded output and
 * diagnostic hygiene. Existing cases in TunerBackupTest and TunerBackupFuzzTest are not repeated here: these probe the
 * migration, interruption and large-input paths those tests do not reach. All values are scratch strings.
 */
class TunerBackupMigrationTest {
    private val sample = BackupContents(
        favorites = setOf("bbc-one", "itv-1", "channel with\ttab"),
        guideMappings = mapOf("bbc-one" to "BBC.One.uk", "itv-1" to "ITV.1"),
        seriesRules = listOf(SeriesRule("news at ten", "bbc-one"), SeriesRule("the archers", null)),
        restrictedGroups = setOf("adult"),
        restrictedChannels = setOf("news three"),
    )

    private fun refuse(text: String): IllegalArgumentException =
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode(text) }

    /** A refusal states a short, fixed reason and never repeats any scratch marker or address. */
    private fun assertClean(message: String?, vararg markers: String) {
        val text = message.orEmpty()
        assertTrue(text.isNotEmpty(), "a refusal must say why")
        assertTrue(text.length <= 120, "a refusal must stay short: $text")
        for (marker in markers) assertTrue(!text.contains(marker), "a refusal echoed a scratch marker")
        assertTrue(!text.contains("://"), "a refusal echoed an address")
    }

    @Test fun aFullBackupRoundTripsAcrossTwoGenerationsWithoutDrift() {
        val first = TunerBackup.encode(sample)
        val once = TunerBackup.decode(first)
        val second = TunerBackup.encode(once)
        val twice = TunerBackup.decode(second)
        assertEquals(sample, once)
        assertEquals(once, twice)
        assertEquals(second, TunerBackup.encode(twice))
    }

    @Test fun anEmptyBackupRoundTripsAsAHeaderOnlyFile() {
        val empty = BackupContents(emptySet(), emptyMap(), emptyList(), emptySet())
        val text = TunerBackup.encode(empty)
        assertEquals(TunerBackup.HEADER + "\n", text)
        assertEquals(empty, TunerBackup.decode(text))
    }

    @Test fun anOlderVersionOneBackupWithoutChannelRecordsMigratesToTheCurrentShape() {
        val legacy = TunerBackup.HEADER +
            "\nfavorite\tbbc-one" +
            "\nmapping\tbbc-one\tBBC.One.uk" +
            "\nseries\tnews at ten\tbbc-one" +
            "\nrestricted\tadult\n"
        val migrated = TunerBackup.decode(legacy)
        assertEquals(setOf("bbc-one"), migrated.favorites)
        assertEquals(mapOf("bbc-one" to "BBC.One.uk"), migrated.guideMappings)
        assertEquals(listOf(SeriesRule("news at ten", "bbc-one")), migrated.seriesRules)
        assertEquals(setOf("adult"), migrated.restrictedGroups)
        assertEquals(emptySet<String>(), migrated.restrictedChannels)
        assertEquals(migrated, TunerBackup.decode(TunerBackup.encode(migrated)))
    }

    @Test fun aValidBodyUnderAnotherVersionIsRefusedWithoutMigration() {
        val body = TunerBackup.encode(sample).removePrefix(TunerBackup.HEADER + "\n")
        for (header in listOf(
            "TUNEROS-BACKUP 0",
            "TUNEROS-BACKUP 2",
            "TUNEROS-BACKUP",
            "TUNEROS-BACKUP 1 ",
            "TUNEROS-BACKUP 1\r",
        )) {
            refuse(header + "\n" + body)
        }
    }

    @Test fun truncatedRecordsAreRefusedWithoutReturningPartialContents() {
        val cases = listOf(
            "favorite\tabc\\",            // cut inside an escape
            "mapping\tabc",              // cut before the second field
            "series\ttitle",            // cut before the channel field
            "restricted",               // cut before the value
            "restricted-channel",       // cut before the value
            "favorite\tabc\nrestricted", // a good record followed by a cut one
        )
        for (body in cases) refuse(TunerBackup.HEADER + "\n" + body)
    }

    @Test fun corruptRecordsAreRefusedWithoutReturningPartialContents() {
        val cases = listOf(
            "favorite\tab\\qcd",                 // unknown escape
            "favorite\ta\tb",                    // extra field
            "favorite\tgood\nfuture\tx",         // unknown kind after a good record
            "favorite\tgood\nmapping\tonly-one", // wrong field count after a good record
        )
        for (body in cases) refuse(TunerBackup.HEADER + "\n" + body)
    }

    @Test fun largeSyntheticInputIsRefusedWithABoundedMessage() {
        val marker = "SCRATCH-SENTINEL-9F2C"
        val tooMany = BackupContents(
            favorites = (1..TunerBackup.MAX_RECORDS + 1).map { "$marker-$it" }.toSet(),
            guideMappings = emptyMap(),
            seriesRules = emptyList(),
            restrictedGroups = emptySet(),
        )
        val byCount = assertFailsWith<IllegalArgumentException> { TunerBackup.encode(tooMany) }
        assertClean(byCount.message, marker)

        // Each value sits exactly at the per-value limit, so the byte limit, not the value limit, is what refuses.
        val wide = BackupContents(
            favorites = (1..TunerBackup.MAX_RECORDS / 4).map {
                val prefix = "$marker-$it-"
                prefix + "v".repeat(TunerBackup.MAX_VALUE_CHARS - prefix.length)
            }.toSet(),
            guideMappings = emptyMap(),
            seriesRules = emptyList(),
            restrictedGroups = emptySet(),
        )
        val byBytes = assertFailsWith<IllegalArgumentException> { TunerBackup.encode(wide) }
        assertClean(byBytes.message, marker)
    }

    @Test fun aLargeButLegalBackupRoundTripsWithinBothLimits() {
        val favorites = (1..900).map { "channel-$it-" + "v".repeat(TunerBackup.MAX_VALUE_CHARS - "channel-$it-".length) }.toSet()
        val large = BackupContents(favorites, emptyMap(), emptyList(), emptySet())
        val text = TunerBackup.encode(large)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= TunerBackup.MAX_BYTES)
        val restored = TunerBackup.decode(text)
        assertEquals(900, restored.favorites.size)
        assertEquals(text, TunerBackup.encode(restored))
    }

    @Test fun diagnosticsNeverRepeatScratchMarkersAddressesOrCredentials() {
        val addressMarker = "SCRATCH-HOST-4C1D"
        val credentialMarker = "SCRATCH-VALUE-77B3"
        val oversized = TunerBackup.HEADER + "\n" + "x".repeat(TunerBackup.MAX_BYTES)
        val truncated = TunerBackup.HEADER + "\nfavorite\tabc\\"
        for (error in listOf(
            refuse(TunerBackup.HEADER + "\nfavorite\ttoken=$credentialMarker\n"),
            refuse(oversized),
            refuse(truncated),
            assertFailsWith<IllegalArgumentException> {
                TunerBackup.encode(sample.copy(favorites = setOf("https://$addressMarker.example/a.m3u8")))
            },
            assertFailsWith<IllegalArgumentException> {
                TunerBackup.encode(sample.copy(restrictedGroups = setOf("password=$credentialMarker")))
            },
        )) {
            assertClean(error.message, addressMarker, credentialMarker)
        }
    }
}

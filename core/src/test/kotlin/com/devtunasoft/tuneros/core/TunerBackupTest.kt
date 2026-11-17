package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TunerBackupTest {
    private val sample = BackupContents(
        favorites = setOf("bbc-one", "itv-1"),
        guideMappings = mapOf("bbc-one" to "BBC.One.uk"),
        seriesRules = listOf(SeriesRule("news at ten", "bbc-one"), SeriesRule("the archers", null)),
        restrictedGroups = setOf("adult"),
    )

    @Test fun encodingThenDecodingRestoresTheSameContents() {
        assertEquals(sample, TunerBackup.decode(TunerBackup.encode(sample)))
    }

    @Test fun theBackupStartsWithItsVersionLine() {
        assertTrue(TunerBackup.encode(sample).startsWith(TunerBackup.HEADER + "\n"))
    }

    @Test fun tabsNewlinesAndBackslashesInValuesSurviveARoundTrip() {
        val odd = BackupContents(
            favorites = setOf("name\twith tab", "line\nbreak", "back\\slash"),
            guideMappings = emptyMap(),
            seriesRules = emptyList(),
            restrictedGroups = emptySet(),
        )
        assertEquals(odd, TunerBackup.decode(TunerBackup.encode(odd)))
    }

    @Test fun aValueThatLooksLikeAUrlIsNeverWritten() {
        val withUrl = sample.copy(favorites = setOf("http://provider.example/live.m3u8"))
        assertFailsWith<IllegalArgumentException> { TunerBackup.encode(withUrl) }
    }

    @Test fun aValueThatLooksLikeACredentialIsNeverWritten() {
        assertFailsWith<IllegalArgumentException> {
            TunerBackup.encode(sample.copy(restrictedGroups = setOf("password=hunter2")))
        }
        assertFailsWith<IllegalArgumentException> {
            TunerBackup.encode(sample.copy(favorites = setOf("token: abc123")))
        }
    }

    @Test fun anImportWithAUrlIsRefusedAsAWhole() {
        val file = TunerBackup.HEADER + "\nfavorite\thttps://provider.example/a.m3u8\n"
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode(file) }
    }

    @Test fun anImportWithAnUnknownRecordKindIsRefused() {
        val file = TunerBackup.HEADER + "\nplaylist\tbbc-one\n"
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode(file) }
    }

    @Test fun anImportWithTheWrongVersionIsRefused() {
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode("TUNEROS-BACKUP 2\n") }
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode("") }
    }

    @Test fun aBackupAboveTheSizeLimitIsRefusedBeforeItIsParsed() {
        val oversized = TunerBackup.HEADER + "\n" + "x".repeat(TunerBackup.MAX_BYTES)
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode(oversized) }
    }

    @Test fun tooManyRecordsAreRefused() {
        val many = (1..TunerBackup.MAX_RECORDS + 1).map { "favorite\tchannel-$it" }
        val file = TunerBackup.HEADER + "\n" + many.joinToString("\n") + "\n"
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode(file) }
    }

    @Test fun aBackupWithTooManyRestrictedGroupsIsRefused() {
        val groups = (1..ParentalPolicy.MAX_RESTRICTED_GROUPS + 1).joinToString("") { "restricted\tgroup-$it\n" }
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode(TunerBackup.HEADER + "\n" + groups) }
    }

    @Test fun aBackupWithTheMostRestrictedGroupsIsAccepted() {
        val groups = (1..ParentalPolicy.MAX_RESTRICTED_GROUPS).joinToString("") { "restricted\tgroup-$it\n" }
        val restored = TunerBackup.decode(TunerBackup.HEADER + "\n" + groups)
        assertEquals(ParentalPolicy.MAX_RESTRICTED_GROUPS, restored.restrictedGroups.size)
    }

    @Test fun aBackupWithABlankRestrictedGroupIsRefused() {
        assertFailsWith<IllegalArgumentException> { TunerBackup.decode(TunerBackup.HEADER + "\nrestricted\t   \n") }
    }

    @Test fun anImportedRestrictedGroupIsStoredNormalized() {
        val restored = TunerBackup.decode(TunerBackup.HEADER + "\nrestricted\t Adult \n")
        assertEquals(setOf("adult"), restored.restrictedGroups)
    }

    @Test fun aValueOverTheLengthLimitIsRefused() {
        val long = "a".repeat(TunerBackup.MAX_VALUE_CHARS + 1)
        assertFailsWith<IllegalArgumentException> { TunerBackup.encode(sample.copy(favorites = setOf(long))) }
    }

    @Test fun theErrorMessageNeverEchoesTheRejectedValue() {
        val error = assertFailsWith<IllegalArgumentException> {
            TunerBackup.decode(TunerBackup.HEADER + "\nfavorite\thttps://secret.example/token=abc\n")
        }
        assertTrue(error.message.orEmpty().isNotEmpty())
        assertTrue(!error.message.orEmpty().contains("secret.example"), "the message must not echo the value")
    }
}

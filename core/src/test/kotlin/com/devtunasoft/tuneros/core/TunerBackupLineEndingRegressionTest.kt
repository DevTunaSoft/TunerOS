package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CORE-46: the backup format is a line-based text format, so a file that travelled through a
 * Windows text editor uses CRLF. Records must still import exactly as exported, while a raw
 * carriage return in a value stays escaped and the URL/credential refusal stays fail-closed.
 *
 * Every input is a synthetic fixture: no provider address, credential, PIN or recording payload
 * is written, read or logged. The inputs are bounded and fully deterministic.
 */
class TunerBackupLineEndingRegressionTest {
    private val contents = BackupContents(
        favorites = setOf("news HD", "sports\\one"),
        guideMappings = mapOf("news HD" to "bbc.news"),
        seriesRules = listOf(SeriesRule("news at ten", "news HD")),
        restrictedGroups = setOf("adults"),
        restrictedChannels = setOf("channel 4"),
    )

    private fun crlf(text: String): String = text.replace("\n", "\r\n")

    @Test fun aCrlfBackupRestoresEveryRecordAndReencodesIdentically() {
        val exported = TunerBackup.encode(contents)
        val decoded = TunerBackup.decode(crlf(exported))
        assertEquals(contents, decoded)
        assertEquals(exported, TunerBackup.encode(decoded))
    }

    @Test fun mixedLineEndingsDoNotCorruptAnyValue() {
        val lines = TunerBackup.encode(contents).lines()
        val mixed = buildString {
            lines.forEachIndexed { index, line ->
                append(line)
                append(if (index % 3 == 0) "\r\n" else "\n")
            }
        }
        assertEquals(contents, TunerBackup.decode(mixed))
    }

    @Test fun anEscapedCarriageReturnInsideAValueSurvivesCrlfLineEndings() {
        val withCarriageReturn = contents.copy(favorites = setOf("news\rHD"))
        val exported = TunerBackup.encode(withCarriageReturn)
        assertTrue(exported.contains("\\r"), "the value must be escaped before it reaches a file")
        assertEquals(withCarriageReturn, TunerBackup.decode(crlf(exported)))
    }

    @Test fun aRawTrailingCarriageReturnIsALineEndingAndNotPartOfAValue() {
        val exported = TunerBackup.encode(contents)
        val withOneRawCr = exported.replace("favorite\tnews HD\n", "favorite\tnews HD\r\n")
        assertTrue(withOneRawCr != exported, "the fixture must contain the targeted record")
        assertEquals(contents, TunerBackup.decode(withOneRawCr))
    }

    @Test fun crlfLineEndingsDoNotWeakenTheUrlAndCredentialRefusal() {
        val header = TunerBackup.HEADER
        val url = assertFailsWith<IllegalArgumentException> {
            TunerBackup.decode("$header\r\nfavorite\thttp://canary.invalid/core46\r\n")
        }
        val credential = assertFailsWith<IllegalArgumentException> {
            TunerBackup.decode("$header\r\nfavorite\ttoken=CANARY_CORE_46\r\n")
        }
        assertTrue(!url.toString().contains("canary", ignoreCase = true))
        assertTrue(!credential.toString().contains("CANARY", ignoreCase = true))
    }

    @Test fun aHeaderTerminatedOnlyByCarriageReturnIsStillRefused() {
        assertFailsWith<IllegalArgumentException> {
            TunerBackup.decode(TunerBackup.HEADER + "\rfavorite\tnews HD\r")
        }
    }
}

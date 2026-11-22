package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Deterministic fuzzing of the backup decoder. Random text and mutated valid backups may only be refused with
 * IllegalArgumentException. When a decode succeeds, the contents must re-encode without an address and must restore
 * themselves exactly. Every iteration is seeded, so a failure reproduces from the iteration number.
 */
class TunerBackupFuzzTest {
    private val fragments = listOf(
        "TUNEROS-BACKUP 1", "\n", "\t", "\"", "\\", "=", ",", ";", "{", "}", "[", "]", ":",
        "http://", "https://", "token", "pin", "favorite", "mapping", "rule", "group", "0", "1", "2", "99", " ", "x", "é",
        "\u0000",
    )

    private val sample = BackupContents(
        favorites = setOf("bbc-one", "itv-1"),
        guideMappings = mapOf("bbc-one" to "BBC.One.uk"),
        seriesRules = listOf(SeriesRule("news at ten", "bbc-one"), SeriesRule("the archers", null)),
        restrictedGroups = setOf("adult"),
    )

    /** Decodes [text]. A refusal returns null; any other exception escapes and fails the test. */
    private fun decodeOrNull(text: String): BackupContents? =
        try {
            TunerBackup.decode(text)
        } catch (refused: IllegalArgumentException) {
            null
        }

    private fun checkAccepted(decoded: BackupContents, iteration: Int) {
        val encoded = TunerBackup.encode(decoded)
        assertFalse(encoded.contains("://"), "iteration $iteration encodes an address")
        val restored = TunerBackup.decode(encoded)
        // encode writes series rules in a fixed order, so the rules must survive as a set and the text must be stable.
        assertEquals(decoded.copy(seriesRules = emptyList()), restored.copy(seriesRules = emptyList()), "iteration $iteration")
        assertEquals(decoded.seriesRules.toSet(), restored.seriesRules.toSet(), "iteration $iteration")
        assertEquals(encoded, TunerBackup.encode(restored), "iteration $iteration encodes differently after a round trip")
    }

    @Test fun randomBackupTextIsRefusedOrRestoresItselfWithoutAnAddress() {
        val random = Random(20261122)
        repeat(2_000) { iteration ->
            val text = buildString {
                repeat(random.nextInt(0, 40)) { append(fragments[random.nextInt(fragments.size)]) }
            }
            decodeOrNull(text)?.let { checkAccepted(it, iteration) }
        }
    }

    @Test fun mutatedValidBackupsAreRefusedOrRestoreThemselvesWithoutAnAddress() {
        val random = Random(4242)
        val valid = TunerBackup.encode(sample)
        repeat(2_000) { iteration ->
            val chars = valid.toCharArray()
            repeat(random.nextInt(1, 4)) {
                chars[random.nextInt(chars.size)] = fragments[random.nextInt(fragments.size)].first()
            }
            val mutated = String(chars)
            decodeOrNull(mutated)?.let { checkAccepted(it, iteration) }
        }
    }
}

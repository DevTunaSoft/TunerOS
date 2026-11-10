package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seeded randomized regression checks, NOT a coverage-guided fuzzer or security audit.
 * Reproducible in CI, with a fixed seed and explicit properties.
 */
class RandomizedRegressionTest {
    @Test fun randomPlaylistImportsKeepIdsUniqueAndIgnoreLocalFiles() {
        val random = Random(20261110)
        repeat(250) {
            val input = buildString {
                append("#EXTM3U\n")
                repeat(random.nextInt(1, 15)) { index ->
                    append("#EXTINF:-1 tvg-id=\"shared\",Channel")
                    append(index)
                    append('\n')
                    append(if (random.nextBoolean()) "https://example.org/$index" else "file:///etc/passwd")
                    append('\n')
                }
            }
            val result = PlaylistParser.parseReport(input.reader())
            assertTrue(result.channels.all { it.streamUrl.startsWith("https://") })
            assertEquals(result.channels.size, result.channels.map { it.id }.distinct().size)
        }
    }

    @Test fun randomOutOfOrderSchedulesMatchLinearReferenceForNonOverlappingEntries() {
        val random = Random(112233)
        repeat(150) {
            val entries = (0..random.nextInt(1, 80)).map { i ->
                GuideProgram("channel", "Show$i", i * 100L, i * 100L + 70)
            }.shuffled(random)
            val guide = GuideSnapshot(entries, emptyMap())
            guide.prepareIndex()
            repeat(25) {
                val time = random.nextLong(0, 9000)
                val expected = entries.firstOrNull { it.startEpochSeconds <= time && time < it.endEpochSeconds }
                assertEquals(expected, guide.now("channel", time))
            }
        }
    }

    @Test fun randomizedDoctypePayloadsNeverResolveExternalEntities() {
        val random = Random(44)
        repeat(75) {
            val name = "e" + random.nextInt(100_000)
            val xml = """<!DOCTYPE tv [<!ENTITY $name SYSTEM "file:///etc/passwd">]><tv><channel id="a"><display-name>&$name;</display-name></channel></tv>"""
            assertFailsWith<Exception> { GuideParser.parse(ByteArrayInputStream(xml.toByteArray())) }
        }
    }
}

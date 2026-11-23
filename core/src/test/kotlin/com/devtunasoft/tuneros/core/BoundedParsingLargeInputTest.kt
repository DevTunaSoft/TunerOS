package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic bounded-parsing regression coverage for the M3U and XMLTV parsers.
 *
 * Fixtures are generated in memory from scratch values: no external files, no
 * provider URLs, no credentials, no media payloads. Two invariants are checked:
 *  - retained collection sizes grow exactly linearly with the accepted input
 *    size (no superlinear/duplicate retention), including at the hard caps;
 *  - repeated parse cycles over the same large input produce identical,
 *    bounded results, proving the singleton parsers keep no cross-call state.
 *
 * Internal pooling maps are bounded in the implementation by fixed caps; the
 * fixtures below deliberately feed far more distinct header/ID values than
 * those caps so the capped paths are exercised on every run.
 */
class BoundedParsingLargeInputTest {

    /** Synthetic M3U. Optionally gives every channel a unique UA/referrer pair. */
    private fun m3u(channelCount: Int, uniqueHeaders: Boolean = false): String = buildString {
        append("#EXTM3U\n")
        repeat(channelCount) { n ->
            append("#EXTINF:-1 tvg-id=\"gid-").append(n)
            append("\" tvg-name=\"Name").append(n).append("\",Name").append(n).append('\n')
            if (uniqueHeaders) {
                append("#EXTVLCOPT:http-user-agent=agent-").append(n).append('\n')
                append("#EXTVLCOPT:http-referer=referer-").append(n).append('\n')
            }
            append("http://example.org/channel/").append(n).append('\n')
        }
    }

    /** Synthetic XMLTV with [channelCount] channels and [programCount] programmes. */
    private fun xmltv(programCount: Int, channelCount: Int): String = buildString {
        append("<tv>\n")
        repeat(channelCount) { c ->
            append("<channel id=\"ch-").append(c).append("\"><display-name>Channel ").append(c)
            append("</display-name></channel>\n")
        }
        repeat(programCount) { p ->
            append("<programme channel=\"ch-").append(p % channelCount)
            append("\" start=\"20261111100000 +0000\" stop=\"20261111110000 +0000\">")
            append("<title>Title ").append(p).append("</title></programme>\n")
        }
        append("</tv>")
    }

    // ---- M3U ----------------------------------------------------------------

    @Test fun m3uRetainedChannelsScaleLinearlyWithInputSize() {
        // Exactly proportional at three sizes: retained channels == accepted input rows.
        val sizes = listOf(1_000, 2_000, 4_000)
        val counts = sizes.map { n ->
            val result = PlaylistParser.parseReport(m3u(n).reader(), maxChannels = sizes.last() + 1)
            assertEquals(n, result.channels.size, "input rows $n")
            assertTrue(result.warnings.isEmpty())
            // Every accepted channel is retained once; no dedup collapse or duplicate retention.
            assertEquals(n, result.channels.map { it.id }.toSet().size, "unique ids for $n")
            result.channels.size
        }
        assertEquals(sizes, counts)
        assertEquals(counts[0] * 2, counts[1])
        assertEquals(counts[0] * 4, counts[2])
    }

    @Test fun m3uCapStopsRetentionWhileInputKeepsGrowing() {
        val cap = 500
        val offered = 8_000
        // Far more distinct UA/referrer values than the shared-header cap, and more
        // channels than the accepted cap: retention must stop at the cap regardless.
        val result = PlaylistParser.parseReport(m3u(offered, uniqueHeaders = true).reader(), maxChannels = cap)
        assertEquals(cap, result.channels.size)
        assertEquals("agent-${cap - 1}", result.channels.last().userAgent)
        assertEquals("referer-${cap - 1}", result.channels.last().referer)
        assertTrue(result.warnings.single().contains("${offered - cap} channels omitted"))
    }

    @Test fun m3uRepeatedParseIsBoundedAndDeterministic() {
        val count = 5_000
        val feed = m3u(count, uniqueHeaders = true)
        val expectedIds = PlaylistParser.parse(feed.reader(), maxChannels = count + 1).map { it.id }
        assertEquals(count, expectedIds.size)
        repeat(5) { cycle ->
            val result = PlaylistParser.parseReport(feed.reader(), maxChannels = count + 1)
            assertEquals(count, result.channels.size, "cycle $cycle")
            assertTrue(result.warnings.isEmpty(), "cycle $cycle")
            assertEquals(expectedIds, result.channels.map { it.id }, "cycle $cycle")
            // Distinct headers are not leaked or merged across cycles.
            assertEquals("agent-0", result.channels.first().userAgent, "cycle $cycle")
            assertEquals("agent-${count - 1}", result.channels.last().userAgent, "cycle $cycle")
        }
    }

    // ---- XMLTV ---------------------------------------------------------------

    @Test fun xmltvRetainedProgramsScaleLinearlyWithInputSize() {
        val channelCount = 50
        val sizes = listOf(1_000, 2_000, 4_000)
        val counts = sizes.map { n ->
            val result = GuideParser.parse(
                ByteArrayInputStream(xmltv(n, channelCount).toByteArray()),
                maxPrograms = sizes.last() + 1,
            )
            assertEquals(n, result.programs.size, "programmes $n")
            assertEquals(channelCount, result.channelNames.size, "channels for $n")
            assertTrue(result.warnings.isEmpty(), "warnings for $n")
            result.programs.size
        }
        assertEquals(sizes, counts)
        assertEquals(counts[0] * 2, counts[1])
        assertEquals(counts[0] * 4, counts[2])
    }

    @Test fun xmltvCapStopsRetentionWhileInputKeepsGrowing() {
        val programCap = 500
        val channelCap = 20
        val offeredPrograms = 8_000
        val offeredChannels = 100
        val result = GuideParser.parse(
            ByteArrayInputStream(xmltv(offeredPrograms, offeredChannels).toByteArray()),
            maxPrograms = programCap,
            maxChannels = channelCap,
        )
        assertEquals(programCap, result.programs.size)
        assertEquals(channelCap, result.channelNames.size)
        assertEquals("Title ${programCap - 1}", result.programs.last().title)
        assertTrue(result.warnings.any { it.contains("${offeredPrograms - programCap} programmes omitted") })
        assertTrue(result.warnings.any { it.contains("${offeredChannels - channelCap} channel names omitted") })
    }

    @Test fun xmltvRepeatedParseIsBoundedAndDeterministic() {
        val channelCount = 100
        val programCount = 5_000
        val bytes = xmltv(programCount, channelCount).toByteArray()
        repeat(5) { cycle ->
            val result = GuideParser.parse(
                ByteArrayInputStream(bytes),
                maxPrograms = programCount + 1,
            )
            assertEquals(programCount, result.programs.size, "cycle $cycle")
            assertEquals(channelCount, result.channelNames.size, "cycle $cycle")
            assertTrue(result.warnings.isEmpty(), "cycle $cycle")
            assertEquals("Title 0", result.programs.first().title, "cycle $cycle")
            assertEquals("Title ${programCount - 1}", result.programs.last().title, "cycle $cycle")
        }
    }
}

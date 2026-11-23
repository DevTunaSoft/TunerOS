package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage for the GuideSnapshot bounded guide index.
 * Every expectation comes from a linear reference over the same synthetic fixtures;
 * no provider data, credentials or real guide content is used.
 */
class GuideSnapshotAdversarialIndexTest {
    private val ordering =
        compareBy<GuideProgram> { it.startEpochSeconds }.thenBy { it.endEpochSeconds }

    private fun linearBetween(entries: List<GuideProgram>, from: Long, to: Long): List<GuideProgram> =
        entries.sortedWith(ordering).filter {
            it.startEpochSeconds < to && it.endEpochSeconds > from
        }

    private fun linearNow(entries: List<GuideProgram>, at: Long): GuideProgram? =
        entries.sortedWith(ordering).lastOrNull {
            it.startEpochSeconds <= at && it.endEpochSeconds > at
        }

    @Test fun extremeLongBoundariesAndNearOverflowWindowsMatchLinearReference() {
        val min = Long.MIN_VALUE
        val max = Long.MAX_VALUE
        val entries = listOf(
            GuideProgram("extreme", "MIN start", min, min + 3),
            GuideProgram("extreme", "spans zero", -2L, 3L),
            GuideProgram("extreme", "zero length", 10L, 10L),
            GuideProgram("extreme", "reversed", 20L, 15L),
            GuideProgram("extreme", "MIN end", 30L, min),
            GuideProgram("extreme", "MAX end", max - 4, max),
            GuideProgram("quiet", "future", max - 1, max),
        )
        val guide = GuideSnapshot(entries, emptyMap())
        guide.prepareIndex()

        val windows = listOf(
            min to min + 3,
            min + 2 to 0L,
            -3L to 4L,
            0L to 1L,
            9L to 11L,
            14L to 21L,
            29L to 31L,
            max - 5 to max,
            max - 1 to max,
            -1L to max,
            min to max,
        )
        val extreme = entries.filter { it.channelId == "extreme" }
        val quiet = entries.filter { it.channelId == "quiet" }
        for ((from, to) in windows) {
            assertEquals(linearBetween(extreme, from, to), guide.programsBetween("extreme", from, to), "window $from..$to")
            assertEquals(linearBetween(quiet, from, to), guide.programsBetween("quiet", from, to), "quiet window $from..$to")
        }
        val times = listOf(
            min, min + 2, min + 3, -3L, -2L, 0L, 2L, 3L, 10L, 15L, 20L, 30L,
            max - 5, max - 1, max,
        )
        for (time in times) {
            assertEquals(linearNow(extreme, time), guide.now("extreme", time), "now at $time")
            assertEquals(linearNow(quiet, time), guide.now("quiet", time), "quiet now at $time")
        }
        assertEquals(max, guide.latestEnd("extreme"))
        assertEquals(max, guide.latestEnd("quiet"))
        assertTrue(guide.hasSchedule("extreme"))
    }

    @Test fun prefixMaximaBinarySearchBoundariesMatchLinearReference() {
        val entries = listOf(
            GuideProgram("boundary", "long", 0L, 1_000L),
            GuideProgram("boundary", "e1", 10L, 30L),
            GuideProgram("boundary", "e2", 40L, 70L),
            GuideProgram("boundary", "e3", 100L, 140L),
            GuideProgram("boundary", "mid", 500L, 560L),
            GuideProgram("boundary", "late", 900L, 950L),
            GuideProgram("boundary", "after", 1_100L, 1_200L),
        ).shuffled(Random(2401))
        val guide = GuideSnapshot(entries, emptyMap())
        guide.prepareIndex()
        val boundaries = listOf(
            -1L, 0L, 9L, 10L, 29L, 30L, 31L, 39L, 40L, 69L, 70L, 71L,
            99L, 100L, 139L, 140L, 141L, 499L, 500L, 559L, 560L, 561L,
            899L, 900L, 949L, 950L, 951L, 999L, 1_000L, 1_001L,
            1_099L, 1_100L, 1_199L, 1_200L,
        )
        for (from in boundaries) {
            assertEquals(linearNow(entries, from), guide.now("boundary", from), "now at $from")
            for (span in listOf(1L, 60L, 1_300L)) {
                val to = from + span
                assertEquals(
                    linearBetween(entries, from, to),
                    guide.programsBetween("boundary", from, to),
                    "window $from..$to",
                )
            }
        }
        assertEquals(1_200L, guide.latestEnd("boundary"))
    }

    @Test fun malformedIntervalsNeverBecomeCurrentButKeepExactWindowSemantics() {
        val min = Long.MIN_VALUE
        val max = Long.MAX_VALUE
        val mixed = listOf(
            GuideProgram("bad", "valid long", 0L, 500L),
            GuideProgram("bad", "zero length", 100L, 100L),
            GuideProgram("bad", "reversed", 200L, 150L),
            GuideProgram("bad", "MIN end", 300L, min),
            GuideProgram("bad", "MAX start", max, max),
        )
        val allMalformed = listOf(
            GuideProgram("badonly", "zero", 10L, 10L),
            GuideProgram("badonly", "reversed", 30L, 20L),
            GuideProgram("badonly", "MIN end", 50L, min),
        )
        val guide = GuideSnapshot(mixed + allMalformed, emptyMap())
        guide.prepareIndex()

        for ((from, to) in listOf(
            -1L to 0L, 0L to 1L, 99L to 101L, 149L to 151L, 199L to 201L,
            299L to 301L, 499L to 500L, 500L to 501L, 0L to 600L, 0L to max,
        )) {
            assertEquals(linearBetween(mixed, from, to), guide.programsBetween("bad", from, to), "bad $from..$to")
            assertEquals(
                linearBetween(allMalformed, from, to),
                guide.programsBetween("badonly", from, to),
                "badonly $from..$to",
            )
        }
        for (time in listOf(
            min, -1L, 0L, 99L, 100L, 101L, 149L, 150L, 151L,
            199L, 200L, 201L, 299L, 300L, 301L, 499L, 500L, 501L, max - 1, max,
        )) {
            assertEquals(linearNow(mixed, time), guide.now("bad", time), "bad now $time")
            assertEquals(null, guide.now("badonly", time), "badonly now $time")
        }
        assertEquals(mixed.maxOf { it.endEpochSeconds }, guide.latestEnd("bad"))
        assertEquals(allMalformed.maxOf { it.endEpochSeconds }, guide.latestEnd("badonly"))
        assertTrue(guide.hasSchedule("badonly"))
    }

    @Test fun emptySingleEntryAndMissingChannelsHaveNeutralIndexSemantics() {
        val empty = GuideSnapshot(emptyList(), mapOf("named" to "Named"))
        empty.prepareIndex()
        assertEquals(false, empty.hasSchedule("named"))
        assertEquals(false, empty.hasSchedule("missing"))
        assertEquals(null, empty.latestEnd("named"))
        assertEquals(null, empty.now("named", 0L))
        assertTrue(empty.programsBetween("named", 0L, 1L).isEmpty())
        assertTrue(empty.currentProgramsAt(0L).isEmpty())
        assertFailsWith<IllegalArgumentException> { empty.programsBetween("named", 1L, 1L) }
        assertFailsWith<IllegalArgumentException> { empty.programsBetween("missing", 2L, 1L) }

        val solo = GuideProgram("solo", "Only", 100L, 200L)
        val single = GuideSnapshot(listOf(solo), emptyMap())
        single.prepareIndex()
        assertTrue(single.hasSchedule("solo"))
        assertEquals(200L, single.latestEnd("solo"))
        assertEquals(null, single.latestEnd("missing"))
        for (time in listOf(99L, 100L, 101L, 199L, 200L, 201L)) {
            assertEquals(linearNow(listOf(solo), time), single.now("solo", time), "solo now $time")
            assertEquals(null, single.now("missing", time))
        }
        for ((from, to) in listOf(0L to 100L, 0L to 101L, 199L to 200L, 200L to 201L, 100L to 101L)) {
            assertEquals(linearBetween(listOf(solo), from, to), single.programsBetween("solo", from, to))
        }
        assertEquals(mapOf("solo" to solo), single.currentProgramsAt(150L))
        assertTrue(single.currentProgramsAt(200L).isEmpty())
    }

    @Test fun largeBoundedOverlappingMultiChannelSetMatchesLinearReference() {
        val random = Random(90210)
        val channelCount = 24
        val perChannel = 250
        val entries = (0 until channelCount * perChannel).map { index ->
            val id = "ch${index % channelCount}"
            val start = (index / channelCount) * 120L + random.nextLong(0L, 40L)
            GuideProgram(id, "p$index", start, start + random.nextLong(1L, 200L))
        }.shuffled(random)
        val byChannel = entries.groupBy { it.channelId }
        val guide = GuideSnapshot(entries, emptyMap())
        guide.prepareIndex()

        for (id in byChannel.keys) {
            val own = byChannel.getValue(id)
            assertEquals(own.maxOf { it.endEpochSeconds }, guide.latestEnd(id), "latest $id")
            assertTrue(guide.hasSchedule(id))
        }
        repeat(60) {
            val id = "ch${random.nextInt(channelCount)}"
            val own = byChannel.getValue(id)
            val from = random.nextLong(-100L, perChannel * 120L + 100L)
            val to = from + random.nextLong(1L, 500L)
            assertEquals(linearBetween(own, from, to), guide.programsBetween(id, from, to), "window $id $from..$to")
            assertEquals(linearNow(own, from), guide.now(id, from), "now $id $from")
        }
        for (time in listOf(0L, 1_200L, 12_000L, 29_999L)) {
            val expected = byChannel.mapValues { (_, own) -> linearNow(own, time) }
                .filterValues { it != null }
            assertEquals(expected, guide.currentProgramsAt(time), "current at $time")
        }
    }

    @Test fun repeatedPrepareIndexIsIdempotentAndDoesNotMutateCallerInput() {
        val entries = listOf(
            GuideProgram("c", "long", 0L, 300L),
            GuideProgram("c", "short", 50L, 80L),
            GuideProgram("c", "bad", 120L, 100L),
            GuideProgram("c", "late", 400L, 450L),
        ).shuffled(Random(77))
        val original = entries.toList()
        val guide = GuideSnapshot(entries, emptyMap())
        guide.prepareIndex()
        val windows = listOf(0L to 400L, 60L to 130L, 110L to 500L)
        val times = listOf(0L, 60L, 110L, 410L)
        val expectedBetween = windows.map { guide.programsBetween("c", it.first, it.second) }
        val expectedNow = times.map { guide.now("c", it) }
        val expectedLatest = guide.latestEnd("c")
        val expectedCurrent = guide.currentProgramsAt(60L)

        repeat(8) {
            guide.prepareIndex()
            assertEquals(expectedBetween, windows.map { guide.programsBetween("c", it.first, it.second) })
            assertEquals(expectedNow, times.map { guide.now("c", it) })
            assertEquals(expectedLatest, guide.latestEnd("c"))
            assertEquals(expectedCurrent, guide.currentProgramsAt(60L))
            assertEquals(original, entries)
            assertEquals(original, guide.programs)
        }
    }

    @Test fun diagnosticsNeverEchoSyntheticSecretMarkers() {
        val token = Random(90211).nextLong(1_000_000_000L, 9_999_999_999L).toString()
        val scheme = charArrayOf('h', 't', 't', 'p', 's', ':', '/', '/').concatToString()
        val host = charArrayOf('m', 'a', 'r', 'k', 'e', 'r', '.', 'i', 'n', 'v', 'a', 'l', 'i', 'd').concatToString()
        val markerUrl = scheme + host + "/watch?token=" + token
        val markerSecret = "pin=" + token
        val markers = listOf(markerUrl, markerSecret, host, token)
        val xml = "<tv><channel id=\"synthetic\"><display-name>Synthetic</display-name></channel>" +
            "<programme channel=\"synthetic\" start=\"20261108100000 +0000\" stop=\"20261108110000 +0000\">" +
            "<title>$markerUrl</title><desc>$markerSecret</desc></programme>" +
            "<programme channel=\"synthetic\" start=\"20261108110000 +0000\" stop=\"20261108120000 +0000\">" +
            "<title>second</title></programme></tv>"
        val guide = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()), maxPrograms = 1)

        assertTrue(guide.warnings.isNotEmpty())
        val warningText = guide.warnings.joinToString("\n")
        for (marker in markers) {
            assertTrue(!warningText.contains(marker), "warning echoed a synthetic marker")
        }
        for (badWindow in listOf(markerUrl to (10L to 10L), "synthetic" to (10L to 5L))) {
            val failure = runCatching { guide.programsBetween(badWindow.first, badWindow.second.first, badWindow.second.second) }
                .exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            val message = failure.message.orEmpty()
            for (marker in markers) {
                assertTrue(!message.contains(marker), "exception echoed a synthetic marker")
            }
        }
        assertEquals(null, guide.now(markerUrl, 0L))
        assertTrue(guide.currentProgramsAt(0L).isEmpty())
        // Caller-owned programme text is data, not a diagnostic: it stays on the returned object.
        assertTrue(guide.programs.any { it.title == markerUrl })
    }
}

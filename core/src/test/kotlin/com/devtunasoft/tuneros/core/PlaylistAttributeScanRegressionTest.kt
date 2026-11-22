package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for quadratic time in the EXTINF attribute scan. An unbounded name pattern tried every start inside a long
 * run of letters, so one 32 KiB line with no '=' took about 19 seconds on the build container. Names are now bounded at
 * 64 characters, which keeps the scan linear. The 5-second limit has a wide margin on both sides: the bounded scan takes
 * milliseconds, and the unbounded scan took about 19 seconds.
 */
class PlaylistAttributeScanRegressionTest {
    private val url = "https://example.invalid/live.m3u8"

    @Test fun aLongRunOfLettersWithoutAnEqualsSignParsesInLinearTime() {
        val letters = "a".repeat(PlaylistParser.MAX_LINE_LENGTH - 64)
        val playlist = "#EXTINF:-1 $letters,Name\n$url\n"
        val started = System.nanoTime()
        val channels = PlaylistParser.parse(playlist.reader())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("Name"), channels.map { it.name })
        assertTrue(elapsedMillis < 5_000, "EXTINF attribute scan took $elapsedMillis ms")
    }

    @Test fun attributesAfterAVeryLongAttributeNameStillParse() {
        val longName = "k".repeat(200)
        val playlist = "#EXTINF:-1 $longName=\"x\" tvg-id=\"guide-1\" tvg-name=\"Tv\",Shown\n$url\n"
        val channel = PlaylistParser.parse(playlist.reader()).single()
        assertEquals("guide-1", channel.guideId)
        assertEquals("Shown", channel.name)
    }
}

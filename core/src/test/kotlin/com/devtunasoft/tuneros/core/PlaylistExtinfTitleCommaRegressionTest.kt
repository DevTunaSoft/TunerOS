package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deterministic, synthetic coverage of the EXTINF display-title boundary in [PlaylistParser].
 *
 * EXTINF places the display title after the first comma that is not inside a quoted attribute value. A title may itself
 * contain commas ("Sports, News"), so the separator is the first unquoted comma, not the last comma on the line. Every
 * URL here is a reserved .invalid fixture; no provider address, credential or media is used.
 */
class PlaylistExtinfTitleCommaRegressionTest {
    private fun channelFor(info: String): Channel {
        val feed = "#EXTM3U\n$info\nhttps://fixture.invalid/live.ts\n"
        return PlaylistParser.parse(feed.reader()).single()
    }

    @Test fun aCommaInsideTheDisplayNameIsKept() {
        val channel = channelFor("#EXTINF:-1,Sports, News")
        assertEquals("Sports, News", channel.name)
    }

    @Test fun severalCommasInTheDisplayNameAreKeptInOrder() {
        val channel = channelFor("#EXTINF:-1,Drama, Mystery, Classics")
        assertEquals("Drama, Mystery, Classics", channel.name)
    }

    @Test fun aCommaInsideAQuotedAttributeDoesNotStartTheTitle() {
        val channel = channelFor("#EXTINF:-1 tvg-logo=\"https://fixture.invalid/a,b.png\",Newsroom")
        assertEquals("Newsroom", channel.name)
        assertEquals("https://fixture.invalid/a,b.png", channel.logoUrl)
    }

    @Test fun aCommaInsideAnUnusedQuotedAttributeDoesNotStartTheTitle() {
        val channel = channelFor("#EXTINF:-1 extra-tag=\"one,two\" tvg-id=\"sid\",Evening, Report")
        assertEquals("Evening, Report", channel.name)
        assertEquals("sid", channel.guideId)
    }

    @Test fun tvgNameFallbackStillAppliesWithoutATitleSeparator() {
        val channel = channelFor("#EXTINF:-1 tvg-name=\"Fallback Station\"")
        assertEquals("Fallback Station", channel.name)
    }
}

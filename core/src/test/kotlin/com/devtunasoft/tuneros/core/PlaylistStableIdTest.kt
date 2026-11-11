package com.devtunasoft.tuneros.core

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** IDs are persisted; optimize encoding without changing any historical identity. */
class PlaylistStableIdTest {
    private val sample = """#EXTM3U
#EXTINF:-1 tvg-id="news",Daily News
https://example.org/live.ts
"""

    @Test fun optimizedIdMatchesOriginalSha256HexEncodingExactly() {
        val actual = PlaylistParser.parseReport(sample.reader(), sourceId = "default").channels.single()
        val key = "default\u0000news\u0000https://example.org/live.ts"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual.id)
        assertTrue(actual.id.matches(Regex("[0-9a-f]{24}")))
    }

    @Test fun providerScopedIdentityAndCaseInsensitiveHttpSchemesArePreserved() {
        val first = PlaylistParser.parseReport(sample.reader(), sourceId = "provider-a").channels.single()
        val second = PlaylistParser.parseReport(sample.reader(), sourceId = "provider-b").channels.single()
        assertNotEquals(first.id, second.id)
        val upper = sample.replace("https://", "HTTPS://")
        val imported = PlaylistParser.parseReport(upper.reader())
        assertEquals(1, imported.channels.size)
    }

    @Test fun repeatedImportKeepsIdentifiersStableForLargePlaylists() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(1_000) { n ->
                append("#EXTINF:-1 tvg-id=\"c").append(n).append("\",Channel").append(n).append('\n')
                append("https://example.org/").append(n).append(".ts\n")
            }
        }
        val first = PlaylistParser.parse(text.reader())
        val second = PlaylistParser.parse(text.reader())
        assertEquals(1_000, first.size)
        assertEquals(first.map { it.id }, second.map { it.id })
    }
}

package com.devtunasoft.tuneros.core

import java.io.StringReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deterministic fuzzing of the HLS playlist parser. Random tag soup, including over-long lines, may only be refused
 * with IllegalArgumentException. A snapshot that comes back must respect the parser's bounds, number its segments in
 * strictly increasing order, and never hold a blank URI. Every iteration is seeded, so a failure reproduces from the
 * iteration number.
 */
class HlsPlaylistFuzzTest {
    private val fragments = listOf(
        "#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:4", "#EXT-X-MEDIA-SEQUENCE:5",
        "#EXT-X-MEDIA-SEQUENCE:-1", "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/key\",IV=0x01",
        "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"", "#EXT-X-KEY:METHOD=NONE", "#EXTINF:4.0,", "#EXTINF:bad,",
        "#EXT-X-BYTERANGE:100@0", "#EXT-X-BYTERANGE:0", "#EXT-X-BYTERANGE:9999999999999999999@1",
        "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\"", "#EXT-X-STREAM-INF:BANDWIDTH=x", "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\"",
        "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-DISCONTINUITY", "#EXT-X-ENDLIST", "#EXT-X-PROGRAM-DATE-TIME:2026-11-22T00:00:00Z",
        "a.ts", "b.ts", "https://h.example/x.ts", "variant.m3u8", "", "# a comment", "\u0000", "é", "\t",
    )

    private val validLines = listOf(
        "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:4", "#EXT-X-MEDIA-SEQUENCE:5",
        "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/key\",IV=0x01", "#EXT-X-KEY:METHOD=NONE", "#EXTINF:4.0,",
        "#EXT-X-BYTERANGE:100@0", "#EXT-X-DISCONTINUITY", "#EXT-X-MAP:URI=\"init.mp4\"", "a.ts", "b.ts",
        "https://h.example/x.ts", "# a comment",
    )

    private val oddLines = listOf(
        "#EXTINF:bad,", "#EXT-X-BYTERANGE:0", "#EXT-X-BYTERANGE:9999999999999999999@1",
        "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"", "\u0000", "é", "\t", "",
    )

    /** A long line, to exercise the per-line cap. */
    private val longLine = "#" + "x".repeat(HlsPlaylistParserCore.MAX_LINE_LENGTH + 10)

    private fun parseOrNull(text: String): HlsPlaylistSnapshot? =
        try {
            HlsPlaylistParserCore.parse(StringReader(text))
        } catch (refused: IllegalArgumentException) {
            null
        }

    private fun checkSnapshot(snapshot: HlsPlaylistSnapshot, iteration: Int, input: String = "") {
        when (snapshot) {
            is HlsMasterPlaylist -> {
                assertTrue(snapshot.variants.size <= HlsPlaylistParserCore.MAX_VARIANTS, "iteration $iteration")
                assertTrue(snapshot.variants.all { it.uri.isNotBlank() }, "iteration $iteration has a blank variant URI")
            }
            is HlsMediaPlaylist -> {
                assertTrue(snapshot.segments.size <= HlsPlaylistParserCore.MAX_RESULT_SEGMENTS, "iteration $iteration")
                assertTrue(snapshot.segments.all { it.uri.isNotBlank() }, "iteration $iteration has a blank segment URI")
                assertTrue(
                    snapshot.segments.zipWithNext().all { (earlier, later) -> later.sequence > earlier.sequence },
                    "iteration $iteration numbers its segments out of order for input:\n$input",
                )
            }
        }
    }

    @Test fun randomPlaylistTextIsRefusedOrReturnsBoundedWellOrderedSnapshots() {
        val random = Random(20261122)
        var accepted = 0
        repeat(1_500) { iteration ->
            val text = buildString {
                append("#EXTM3U\n")
                repeat(random.nextInt(0, 30)) {
                    // Mostly well-formed lines, so many playlists parse, plus a share of malformed and over-long ones.
                    val line = when (random.nextInt(10)) {
                        0 -> oddLines[random.nextInt(oddLines.size)]
                        1 -> if (random.nextInt(4) == 0) longLine else validLines[random.nextInt(validLines.size)]
                        else -> validLines[random.nextInt(validLines.size)]
                    }
                    append(line).append('\n')
                }
            }
            parseOrNull(text)?.let {
                checkSnapshot(it, iteration, text)
                accepted++
            }
        }
        // Refusing everything would pass vacuously, so enough random playlists must parse to be checked.
        assertTrue(accepted >= 100, "only $accepted of 1500 random playlists parsed")
    }

    @Test fun mediaPlaylistsWithRandomSequencesStayOrderedAndBounded() {
        val random = Random(777)
        var accepted = 0
        repeat(1_000) { iteration ->
            val first = random.nextLong(-5L, 1_000L)
            val text = buildString {
                append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:$first\n")
                repeat(random.nextInt(0, 12)) {
                    append("#EXTINF:4.0,\n")
                    if (random.nextInt(4) == 0) append("#EXT-X-BYTERANGE:${random.nextInt(0, 500)}@${random.nextInt(0, 500)}\n")
                    append("seg-${random.nextInt(0, 100)}.ts\n")
                }
                if (random.nextBoolean()) append("#EXT-X-ENDLIST\n")
            }
            val after = if (random.nextBoolean()) random.nextLong(-5L, 1_000L) else null
            val snapshot = try {
                HlsPlaylistParserCore.parse(StringReader(text), afterSequenceExclusive = after)
            } catch (refused: IllegalArgumentException) {
                null
            }
            snapshot?.let {
                checkSnapshot(it, iteration, text)
                accepted++
            }
        }
        assertTrue(accepted >= 500, "only $accepted of 1000 generated media playlists parsed")
    }
}

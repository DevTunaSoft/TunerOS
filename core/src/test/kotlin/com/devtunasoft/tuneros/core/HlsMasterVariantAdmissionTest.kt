package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic coverage of the HLS master-playlist admission seam. Fixtures use a reserved .invalid host, synthetic
 * relative segment names and no media payload. No clock, locale, network or provider data is involved.
 */
class HlsMasterVariantAdmissionTest {
    private class RecordingSource(
        private val master: LoadedHlsPlaylist,
    ) : HlsCaptureSource {
        val loadedUrls = ArrayList<String>()

        override fun loadPlaylist(url: String, afterSequenceExclusive: Long?): LoadedHlsPlaylist {
            loadedUrls += url
            return if (url == master.effectiveUrl) {
                master
            } else {
                LoadedHlsPlaylist(
                    url,
                    HlsMediaPlaylist(
                        mediaSequence = 0,
                        targetDurationSeconds = 2,
                        endList = true,
                        segments = emptyList(),
                    ),
                )
            }
        }

        override fun copySegment(
            url: String,
            byteRange: HlsByteRange?,
            output: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): StreamRecorder.Result = error("this fixture never copies a segment")

        override fun loadKey(url: String): ByteArray = error("this fixture has no keys")
    }

    private fun run(master: HlsMasterPlaylist): Pair<HlsCaptureEngine.Result, RecordingSource> {
        val source = RecordingSource(LoadedHlsPlaylist("https://fixture.invalid/master.m3u8", master))
        val result = HlsCaptureEngine().record(
            initialUrl = "https://fixture.invalid/master.m3u8",
            output = ByteArrayOutputStream(),
            endEpochSeconds = 1_000,
            source = source,
            cancelled = { false },
            nowEpochSeconds = { 100 },
            sleepMillis = {},
        )
        return result to source
    }

    @Test fun equalBandwidthVariantsChooseTheGreatestUriAndStayDeterministic() {
        val master = HlsMasterPlaylist(
            listOf(
                HlsVariant("a/live.m3u8", 1_000),
                HlsVariant("b/live.m3u8", 1_000),
            ),
        )
        repeat(3) {
            val (result, source) = run(master)
            assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
            assertEquals(
                listOf(
                    "https://fixture.invalid/master.m3u8",
                    "https://fixture.invalid/b/live.m3u8",
                ),
                source.loadedUrls,
            )
        }
    }

    @Test fun aValidVariantIsStillAdmittedBesideExternalRenditionVariants() {
        val master = HlsMasterPlaylist(
            variants = listOf(
                HlsVariant("external/live.m3u8", 9_000, audioGroup = "aud"),
                HlsVariant("plain/live.m3u8", 500),
            ),
            externalAudioGroups = setOf("aud"),
        )
        val (result, source) = run(master)
        assertEquals(HlsCaptureEngine.StopReason.END_LIST, result.stopReason)
        assertEquals("https://fixture.invalid/plain/live.m3u8", source.loadedUrls.last())
        assertFalse(source.loadedUrls.any { it.contains("/external/") })
    }

    @Test fun aMasterWhoseOnlyVariantNeedsAnExternalRenditionIsRefusedWithoutLoadingIt() {
        val master = HlsMasterPlaylist(
            variants = listOf(HlsVariant("external/live.m3u8", 9_000, audioGroup = "aud")),
            externalAudioGroups = setOf("aud"),
        )
        val (result, source) = run(master)
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals(listOf("https://fixture.invalid/master.m3u8"), source.loadedUrls)
    }

    @Test fun aNonPositiveBandwidthVariantIsNotAdmitted() {
        // RFC 8216 BANDWIDTH is a decimal-integer (>= 1). Zero and negative values are malformed metadata: admitting
        // one would record a variant the provider did not describe with a usable bitrate.
        val master = HlsMasterPlaylist(
            listOf(
                HlsVariant("zero/live.m3u8", 0),
                HlsVariant("negative/live.m3u8", -1),
            ),
        )
        val (result, source) = run(master)
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals(listOf("https://fixture.invalid/master.m3u8"), source.loadedUrls)
    }

    @Test fun aVariantWithoutBandwidthIsNotAdmitted() {
        val master = HlsMasterPlaylist(listOf(HlsVariant("unknown/live.m3u8", null)))
        val (result, source) = run(master)
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertEquals(listOf("https://fixture.invalid/master.m3u8"), source.loadedUrls)
    }

    @Test fun anAdmissionRefusalNeverEchoesSyntheticVariantUriMaterial() {
        val marker = "VARIANT_CANARY"
        val master = HlsMasterPlaylist(
            variants = listOf(HlsVariant("$marker/live.m3u8", 9_000, videoGroup = "cam")),
            externalVideoGroups = setOf("cam"),
        )
        val (result, _) = run(master)
        assertEquals(HlsCaptureEngine.StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.detail.orEmpty().isNotBlank())
        assertFalse(result.detail!!.contains(marker), "detail leaked the variant URI: ${result.detail}")
    }
}

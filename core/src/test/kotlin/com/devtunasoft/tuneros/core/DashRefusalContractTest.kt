package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CORE-31 audit slice: the DASH planner's fail-closed refusal reasons. The existing DASH tests check
 * that hostile manifests give an outcome and that planned fields are well formed, but none asserts
 * that a refusal names a fixed rule, stays bounded, is repeated deterministically, or never echoes
 * text from the manifest. The canary is derived from a seed at run time, never written as a literal.
 */
class DashRefusalContractTest {
    private val canary = "CAN" + Integer.toHexString(0x5EED2026).uppercase()

    private fun document(mpdAttrs: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" $mpdAttrs>
        <Period id="p1"><AdaptationSet contentType="video" mimeType="video/mp4">
        <Representation id="v1-$canary" bandwidth="1000">
        <SegmentTemplate timescale="1000" duration="1000" startNumber="1"
          media="@Number@.m4s" initialization="init.m4s">$body</SegmentTemplate>
        </Representation></AdaptationSet></Period>
        </MPD>
    """.trimIndent().replace("@", "$")

    private fun refusal(text: String, label: String): String {
        val first = DashManifest.plan(text)
        assertTrue(first is DashPlanOutcome.Unsupported, "$label: expected a refusal, got $first")
        val reason = first.reason
        // A fixed rule is named; no manifest text may reach the diagnostic.
        assertTrue(reason.length in 8..120, "$label: unreasonable reason length ${reason.length}")
        assertFalse(reason.contains(canary), "$label: canary leaked into the refusal reason")
        assertEquals(reason, (DashManifest.plan(text) as DashPlanOutcome.Unsupported).reason, "$label: reason is not deterministic")
        return reason
    }

    @Test fun eachHostileManifestRefusesForItsOwnFixedReason() {
        val validAttrs = "type=\"static\" mediaPresentationDuration=\"PT4S\""
        val cases = listOf(
            "unknown type" to document(
                "type=\"odd-$canary\" mediaPresentationDuration=\"PT4S\"", "",
            ) to "Unknown DASH manifest type",
            "two periods" to document(validAttrs, "").replace(
                "</Period>", "</Period><Period id=\"p2\"><AdaptationSet contentType=\"video\"/></Period>",
            ) to "DASH manifests with exactly one period are supported",
            "separate rendition" to document(validAttrs, "").replace(
                "contentType=\"video\"", "contentType=\"audio-$canary\"",
            ).replace(
                "mimeType=\"video/mp4\"", "mimeType=\"audio/mp4\"",
            ) to "Separate audio or text renditions are not supported",
            "protection" to document(validAttrs, "").replace(
                "<AdaptationSet", "<ContentProtection schemeIdUri=\"$canary\"/><AdaptationSet",
            ) to "DASH ContentProtection is not supported",
            "bad timescale" to document(validAttrs, "").replace(
                "timescale=\"1000\"", "timescale=\"0\"",
            ) to "DASH timescale is not a positive number",
            "unknown template token" to document(validAttrs, "").replace(
                "media=\"\$Number\$.m4s\"", "media=\"$canary-\$Bogus\$.m4s\"",
            ) to "DASH media template uses a token that is not supported",
            "timeline gap" to document(
                validAttrs, "<SegmentTimeline><S t=\"0\" d=\"1000\"/><S t=\"3000\" d=\"1000\"/></SegmentTimeline>",
            ) to "DASH SegmentTimeline is not contiguous; gaps are not supported",
            "static open repeat" to document(
                validAttrs, "<SegmentTimeline><S t=\"0\" d=\"1000\" r=\"-1\"/></SegmentTimeline>",
            ) to "DASH SegmentTimeline repeats to the end only in live manifests",
        )
        for ((case, expected) in cases) {
            val (label, text) = case
            assertEquals(expected, refusal(text, label), label)
        }
    }

    @Test fun oversizedAndDtdManifestsRefuseBeforeAnyTextIsEchoed() {
        val oversized = document("type=\"static\" mediaPresentationDuration=\"PT4S\"", "") + canary
            .padStart(DashManifest.MAX_MANIFEST_CHARS + 1, 'x')
        assertEquals("DASH manifest is too large", refusal(oversized, "oversized manifest"))

        val withDtd = document("type=\"static\" mediaPresentationDuration=\"PT4S\"", "")
            .replace("<?xml", "<!DOCTYPE MPD [ <!ENTITY marker \"$canary\"> ]><?xml")
        assertEquals(
            "DASH manifest declares a DTD, which is not accepted",
            refusal(withDtd, "DTD manifest"),
        )
    }
}

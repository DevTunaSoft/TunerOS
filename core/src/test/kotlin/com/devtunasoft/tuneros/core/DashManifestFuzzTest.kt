package com.devtunasoft.tuneros.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deterministic fuzzing of the DASH manifest planner. Generated manifests, with valid and invalid timescales, durations,
 * start numbers, templates, timelines and presentation types, must always give an outcome, never an exception. A plan
 * that comes back must have a positive timescale and segment duration, a non-negative start number, a non-blank media
 * template, and at least one segment when it is static. Every iteration is seeded, so a failure reproduces from the
 * iteration number.
 */
class DashManifestFuzzTest {
    private fun pick(random: Random, vararg options: String): String = options[random.nextInt(options.size)]

    /** The first option about four times in five; otherwise one of the others. */
    private fun weighted(random: Random, vararg options: String): String =
        if (random.nextInt(5) == 0) options[random.nextInt(options.size)] else options[0]

    private fun manifest(random: Random): String {
        // Mostly valid values, so many manifests are planned; the first option is the valid one, chosen about 80% of the time.
        val timescale = weighted(random, "1000", "0", "-3", "x")
        val duration = weighted(random, "2000", "0", "x")
        val start = weighted(random, "1", "-1", "9999999999999999999")
        val offset = weighted(random, "0", "-1", "3000")
        val media = weighted(random, "seg-\$Number\$.m4s", "seg-\$Time\$.m4s", "seg-\$Number%05d\$.m4s", "seg-\$Bandwidth\$.m4s", "seg.m4s")
        val type = weighted(random, "static", "dynamic")
        val presentation = weighted(random, "PT60S", "PT0S", "garbage")
        val availability = weighted(random, "2026-11-22T00:00:00Z", "garbage")
        val timeline = if (random.nextBoolean()) {
            buildString {
                append("<SegmentTimeline>")
                repeat(random.nextInt(0, 5)) {
                    append("<S t=\"${random.nextInt(0, 100_000)}\" d=\"${pick(random, "2000", "4000", "0", "x")}\" ")
                    append("r=\"${pick(random, "0", "1", "2", "-1")}\"/>")
                }
                append("</SegmentTimeline>")
            }
        } else {
            ""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" mediaPresentationDuration="$presentation" availabilityStartTime="$availability" minimumUpdatePeriod="PT2S">
<Period id="p1" start="PT0S">
<AdaptationSet contentType="video" mimeType="video/mp4">
<Representation id="v1" bandwidth="${random.nextInt(0, 5_000_000)}" codecs="avc1.4d401f">
<SegmentTemplate timescale="$timescale" duration="$duration" startNumber="$start" presentationTimeOffset="$offset" media="$media" initialization="init.mp4">$timeline</SegmentTemplate>
</Representation>
</AdaptationSet>
</Period>
</MPD>"""
    }

    private fun checkPlan(plan: DashPlan, iteration: Int, input: String) {
        assertTrue(plan.timescale > 0, "iteration $iteration plans a zero timescale")
        assertTrue(plan.segmentDurationTicks > 0, "iteration $iteration plans a zero-length segment")
        assertTrue(plan.startNumber >= 0, "iteration $iteration plans a negative start number")
        // A listed plan (SegmentList, SegmentTimeline, SegmentBase) carries its media per segment, so its own template
        // is blank by design. Only a plain-template plan must have one.
        if (plan.listedSegments == null) {
            assertTrue(plan.mediaTemplate.isNotBlank(), "iteration $iteration plans a blank media template for:\n$input")
        }
        plan.segmentCount?.let { assertTrue(it >= 1, "iteration $iteration plans a static presentation with no segments") }
    }

    @Test fun generatedManifestsGiveAnOutcomeAndPlannedOnesAreWellFormed() {
        val random = Random(20261122)
        var planned = 0
        repeat(2_000) { iteration ->
            val text = manifest(random)
            when (val outcome = DashManifest.plan(text)) {
                is DashPlanOutcome.Planned -> {
                    checkPlan(outcome.plan, iteration, text)
                    planned++
                }
                is DashPlanOutcome.Unsupported, is DashPlanOutcome.NeedsIndex -> Unit
            }
        }
        // Refusing everything would pass vacuously, so enough generated manifests must be planned to be checked.
        assertTrue(planned >= 100, "only $planned of 2000 generated manifests were planned")
    }

    @Test fun randomJunkGivesAnOutcomeWithoutAnException() {
        val random = Random(4545)
        val junk = listOf("<MPD", "<Period", ">", "/", "\"x\"", "<!DOCTYPE MPD>", "&amp;", "\u0000", "é", " ", "</MPD>")
        repeat(1_000) {
            val text = buildString { repeat(random.nextInt(0, 60)) { append(junk[random.nextInt(junk.size)]) } }
            DashManifest.plan(text)
        }
    }
}

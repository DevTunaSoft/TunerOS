package com.devtunasoft.tuneros.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CORE-19 adversarial coverage for the two diagnostic seams. All inputs are synthetic scratch data:
 * marker strings stand in for credentials/PINs and example.org addresses are reserved documentation hosts.
 *
 * Interpretation of "fail closed" here: a diagnostic emits only fixed safe text or a known-safe
 * parser warning template; malformed or hostile input is dropped, never repaired or echoed.
 */
class DoctorAdversarialRegressionTest {
    private fun guide() = GuideSnapshot(
        programs = listOf(GuideProgram("g1", "SYNTH show", 0L, 3_600L)),
        channelNames = mapOf("g1" to "SYNTH name"),
    )

    private fun diagnosis() = GuideDoctor.diagnose(
        listOf(Channel("c1", "SYNTH channel", "https://example.org/synth", guideId = "g1")),
        emptyMap(),
        guide(),
        1_000L,
    )

    @Test fun summaryDropsWarningsThatCarryCredentialsOrPins() {
        val text = GuideDoctor.summaryText(
            diagnosis(),
            listOf(
                "provider PIN SYNTHPIN4821 expired",
                "auth token SYNTHSECRET for provider",
                "login user:SYNTHPASS",
                "https://provider.example/x?token=SYNTH",
                "3 programmes had invalid timestamps",
            ),
            1_000L,
        )
        assertFalse(text.contains("SYNTHPIN4821"), text)
        assertFalse(text.contains("SYNTHSECRET"), text)
        assertFalse(text.contains("SYNTHPASS"), text)
        assertFalse(text.contains("provider.example"), text)
        assertFalse(text.contains("token"), text)
        assertFalse(text.contains("://"), text)
        assertTrue(text.contains("- 3 programmes had invalid timestamps"), text)
    }

    @Test fun summaryStaysBoundedWhenFloodedWithWarnings() {
        val text = GuideDoctor.summaryText(
            diagnosis(),
            List(5_000) { "3 programmes had invalid timestamps" },
            1_000L,
        )
        assertTrue(text.length <= 2_000, "summary length ${text.length}")
        assertTrue(text.lines().count { it.startsWith("- ") } <= 8, text)
    }

    @Test fun summaryFormatsExtremeEpochsWithoutThrowingOrEchoingThem() {
        val extreme = GuideSnapshot(
            programs = listOf(GuideProgram("g1", "SYNTH show", Long.MIN_VALUE, Long.MAX_VALUE)),
            channelNames = mapOf("g1" to "SYNTH name"),
        )
        val diagnosis = GuideDoctor.diagnose(emptyList(), emptyMap(), extreme, Long.MAX_VALUE)
        val text = GuideDoctor.summaryText(diagnosis, emptyList(), Long.MAX_VALUE)
        assertTrue(text.length <= 2_000, text)
        assertFalse(text.contains("9223372036854775807"), text)
        assertFalse(text.contains("-9223372036854775808"), text)
        assertTrue(text.contains("guide ends:"), text)
    }

    @Test fun doctorObjectsRetainNoInstanceState() {
        for (type in listOf(StreamDoctor::class.java, GuideDoctor::class.java)) {
            val instanceFields = type.declaredFields.filter { field ->
                !Modifier.isStatic(field.modifiers) && !field.isSynthetic
            }
            assertTrue(instanceFields.isEmpty(), "$type retained ${instanceFields.map { it.name }}")
        }
    }

    @Test fun diagnosisDoesNotDependOnCallHistory() {
        val other = GuideSnapshot(
            programs = listOf(GuideProgram("g2", "SYNTH other", 4_000L, 8_000L)),
            channelNames = mapOf("g2" to "SYNTH other"),
        )
        val first = GuideDoctor.summaryText(diagnosis(), emptyList(), 1_000L)
        GuideDoctor.diagnose(listOf(Channel("c2", "SYNTH other", "https://example.org/other")), emptyMap(), other, 9L)
        GuideDoctor.summaryText(GuideDoctor.diagnose(emptyList(), emptyMap(), other, 9L), emptyList(), 9L)
        assertEquals(first, GuideDoctor.summaryText(diagnosis(), emptyList(), 1_000L))
    }

    @Test fun hugeMalformedEvidenceProducesOnlyFixedText() {
        val hostile = StreamEvidence(
            httpStatus = Int.MAX_VALUE,
            contentType = "t".repeat(1_000_000) + "/video?token=SYNTH",
            transport = TransportFault.DNS,
        )
        assertEquals("", StreamDoctor.describe(hostile.copy(httpStatus = Int.MAX_VALUE, transport = null)))
        val text = StreamDoctor.report(Int.MIN_VALUE, hostile)
        assertTrue(text.length <= 400, "${text.length}")
        assertTrue(text.contains("DNS lookup failed"), text)
        assertFalse(text.contains("SYNTH"), text)
        assertFalse(text.contains("2147483647"), text)
    }

    @Test fun reportDropsEveryCredentialShapedContentTypeWithoutEcho() {
        for (raw in listOf(
            "text/html; token=SYNTH",
            "text/html?pin=4821",
            "user:SYNTHPASS@host/video",
            "text/html\r\nset-cookie: SYNTH",
            "text/html\u0000SYNTH",
        )) {
            val text = StreamDoctor.report(2004, StreamEvidence(httpStatus = 200, contentType = raw))
            assertFalse(text.contains("SYNTH"), text)
            assertFalse(text.contains("4821"), text)
            assertFalse(text.contains("SYNTHPASS"), text)
            assertTrue(text.length <= 400, text)
        }
    }

    @Test fun repeatedCyclesKeepOutputsStable() {
        val streamA = StreamEvidence(httpStatus = 404, contentType = "text/html")
        val streamB = StreamEvidence(httpStatus = 599, contentType = "video/mp2t", transport = TransportFault.TLS)
        val firstA = StreamDoctor.report(2004, streamA)
        val firstB = StreamDoctor.report(3003, streamB)
        val guide = diagnosis()
        val firstGuide = GuideDoctor.summaryText(guide, listOf("3 programmes had invalid timestamps"), 1_000L)
        repeat(2_000) {
            assertEquals(firstA, StreamDoctor.report(2004, streamA))
            assertEquals(firstB, StreamDoctor.report(3003, streamB))
            assertEquals(firstGuide, GuideDoctor.summaryText(guide, listOf("3 programmes had invalid timestamps"), 1_000L))
        }
    }

    @Test fun suggestionLimitHandlesBoundaryValues() {
        val names = (1..10).associate { "n$it" to "News $it" }
        val guide = GuideSnapshot(names.keys.map { GuideProgram(it, "Show", 0L, 100L) }, names)
        val channel = Channel("c", "News", "https://example.org/c")
        for (limit in listOf(Int.MIN_VALUE, -1, 0)) {
            assertTrue(GuideDoctor.suggestions(channel, names, guide::hasSchedule, limit).isEmpty())
        }
        assertEquals(1, GuideDoctor.suggestions(channel, names, guide::hasSchedule, 1).size)
        assertTrue(GuideDoctor.suggestions(channel, names, guide::hasSchedule, Int.MAX_VALUE).size <= names.size)
    }
}

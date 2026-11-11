package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamDoctorTest {
    @Test fun classifiesNetworkAndTimeoutSeparately() {
        assertEquals("Network connection failed", StreamDoctor.diagnose(2001).issue)
        assertEquals("Connection timed out", StreamDoctor.diagnose(2002).issue)
        assertEquals("Connection timed out", StreamDoctor.diagnose(1003).issue)
    }

    @Test fun classifiesProviderAccessAndFormatFailures() {
        assertEquals("Provider stream limit", StreamDoctor.diagnose(-104).issue)
        assertEquals("Stream server rejected playback", StreamDoctor.diagnose(2004).issue)
        assertEquals("Unsupported media format", StreamDoctor.diagnose(3004).issue)
        assertEquals("Unsupported media format", StreamDoctor.diagnose(4005).issue)
        assertEquals("Protected media issue", StreamDoctor.diagnose(6008).issue)
        assertEquals("Live window moved", StreamDoctor.diagnose(1002).issue)
    }

    @Test fun everyDiagnosticContainsOnlyBoundedStaticText() {
        val inputCodes = intArrayOf(
            Int.MIN_VALUE, -106, -104, -102, 1002, 1003,
            2001, 2002, 2003, 2004, 2005, 2006, 2007,
            3001, 3002, 3003, 3004, 4001, 4002, 4003, 4004, 4005, 4006,
            6000, 6008, 6099, Int.MAX_VALUE,
        )
        for (code in inputCodes) {
            val result = StreamDoctor.diagnose(code)
            assertTrue(result.issue.length in 4..80)
            assertTrue(result.nextStep.length in 8..120)
            assertFalse(result.issue.contains("http://"))
            assertFalse(result.nextStep.contains("http://"))
            assertFalse(result.issue.contains("token", ignoreCase = true))
            assertFalse(result.nextStep.contains("password", ignoreCase = true))
        }
        assertEquals("Playback failed", StreamDoctor.diagnose(Int.MAX_VALUE).issue)
    }
}

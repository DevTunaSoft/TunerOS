package com.devtunasoft.tuneros.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SEC-01 regression: the shareable Guide Doctor summary promises never to carry credentials. A warning can hold a
 * bare credential assignment with no URL for the URL filter to catch, so each such warning must be dropped.
 */
class GuideDoctorSummaryPrivacyTest {
    private fun summaryWith(warning: String): String {
        val empty = GuideSnapshot(emptyList(), emptyMap())
        val diagnosis = GuideDoctor.diagnose(emptyList(), emptyMap(), empty, 0L)
        return GuideDoctor.summaryText(diagnosis, listOf(warning), 0L)
    }

    @Test fun aBareTokenAssignmentInAWarningIsNotCopiedIntoTheReport() {
        val text = summaryWith("provider auth failed: token=SYNTHETIC-CRED-abc123")
        assertFalse(text.contains("SYNTHETIC-CRED-abc123"), text)
    }

    @Test fun aBarePasswordAssignmentInAWarningIsNotCopiedIntoTheReport() {
        val text = summaryWith("login rejected; password: SYNTHETIC-CRED-abc123")
        assertFalse(text.contains("SYNTHETIC-CRED-abc123"), text)
    }

    @Test fun anApiKeyAssignmentInAWarningIsNotCopiedIntoTheReport() {
        val text = summaryWith("sync failed, api_key=SYNTHETIC-CRED-abc123")
        assertFalse(text.contains("SYNTHETIC-CRED-abc123"), text)
    }

    @Test fun anAuthorizationHeaderInAWarningIsNotCopiedIntoTheReport() {
        val text = summaryWith("auth rejected: Authorization: Bearer SYNTHETIC-CRED-abc123")
        assertFalse(text.contains("SYNTHETIC-CRED-abc123"), text)
    }

    @Test fun aCredentialFreeWarningIsStillShown() {
        val text = summaryWith("3 programmes lacked timezone: assumed UTC")
        assertTrue(text.contains("- 3 programmes lacked timezone: assumed UTC"), text)
    }
}

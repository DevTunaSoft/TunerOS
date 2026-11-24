package com.devtunasoft.tuneros.mnf07

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MNF-07 source-contract regressions for the AndroidManifest notification
 * permission and component declarations. The fixtures are synthetic source
 * text only - no device, URL, credential, media or android.* stub is involved.
 */
class Mnf07ManifestContractTest {
    private val manifest: String get() = Mnf07Sources.compact(Mnf07Sources.manifest())

    @Test
    fun postNotificationsPermissionIsDeclaredExactlyOnce() {
        val declaration =
            Regex("<uses-permission\\s+android:name=\"android.permission.POST_NOTIFICATIONS\"\\s*/>")
        val count = declaration.findAll(manifest).count()
        assertEquals(
            "targetSdk 35 recording service posts foreground notifications; POST_NOTIFICATIONS must be declared once",
            1,
            count,
        )
    }

    @Test
    fun notificationBehaviorJustifiesThePermission() {
        val service = Mnf07Sources.compact(Mnf07Sources.service())
        assertTrue("DVR service must start a foreground notification", service.contains("startForeground("))
        assertTrue("DVR service must create its notification channel", service.contains("createNotificationChannel()"))
        assertTrue("channel must be constructed", service.contains("NotificationChannel("))

        val gradle = Mnf07Sources.compact(Mnf07Sources.buildGradle())
        assertTrue(
            "POST_NOTIFICATIONS is a runtime permission only from Android 13 (API 33)",
            Regex("targetSdk\\s*=\\s*(3[3-9]|[4-9][0-9])").containsMatchIn(gradle),
        )
    }

    @Test
    fun committedComponentsAreDeclaredExactlyOnce() {
        val components = listOf(".DvrRecordingService", ".RecordingAlarmReceiver", ".RecordingRearmReceiver")
        for (component in components) {
            val className = component.removePrefix(".")
            assertTrue(
                "manifest declaration needs a committed class: $className",
                Mnf07Sources.hasClassDeclaration(className),
            )
            val count = Regex("android:name=\"${Regex.escape(component)}\"").findAll(manifest).count()
            assertEquals("exactly one declaration for $component", 1, count)
        }
    }

    @Test
    fun unmergedC3ReceiverIsNotDeclared() {
        assertFalse(
            "UpgradeRearmReceiver belongs to unmerged C3 REL-01; do not declare a class that is not committed",
            manifest.contains("android:name=\".UpgradeRearmReceiver\""),
        )
        assertFalse(
            "C3 receiver class is not committed in this worktree",
            Mnf07Sources.hasClassDeclaration("UpgradeRearmReceiver"),
        )
    }
}

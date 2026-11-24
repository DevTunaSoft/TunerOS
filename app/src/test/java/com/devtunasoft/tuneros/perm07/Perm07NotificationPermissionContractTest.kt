package com.devtunasoft.tuneros.perm07

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERM-07 source-contract regressions for MainActivity's foreground-recording
 * start path. At targetSdk 35 the recording service posts a foreground
 * notification, so POST_NOTIFICATIONS is a runtime permission on Android 13+.
 * These checks read synthetic source text only: no device, no dialog, no
 * permission grant, no media, credentials or provider URL.
 */
class Perm07NotificationPermissionContractTest {
    private val source: String get() = Perm07Sources.compact(Perm07Sources.mainActivity())

    private fun member(signature: String): String = Perm07Sources.member(Perm07Sources.mainActivity(), signature)

    @Test
    fun recordButtonDelegatesToGuardedStart() {
        val body = member("private fun recordFocusedProgram()")
        assertTrue("record button must route through the permission gate", body.contains("startRecordingWithNotificationPermission(intent)"))
        assertFalse("record button must not start the service directly", body.contains("startForegroundService("))
    }

    @Test
    fun foregroundStartsAreOnlyInTheTwoGuardedHelpers() {
        assertEquals(
            "only the gate and the result handler may start the foreground service",
            2,
            Regex("startForegroundService\\(").findAll(source).count(),
        )
    }

    @Test
    fun gateStartsImmediatelyOnlyWhenGrantedOtherwiseQueues() {
        val body = member("private fun startRecordingWithNotificationPermission(intent: Intent)")
        assertTrue("gate must check the grant state", body.contains("if (postNotificationsGranted())"))
        val start = body.indexOf("startForegroundService(intent)")
        assertTrue("granted branch must start the service", start >= 0)
        val ret = body.indexOf("return", start)
        assertTrue("granted branch must return before the request path", ret > start)
        val queued = body.indexOf("pendingRecordingStart = intent")
        assertTrue("denied path must queue the intent", queued > ret)
        assertTrue("denied path must request the permission", body.indexOf("requestPostNotificationsOnce()") > queued)
    }

    @Test
    fun grantCheckIsApiGatedAndUsesFrameworkConstant() {
        val body = member("private fun postNotificationsGranted(): Boolean")
        assertTrue("runtime permission only exists from Android 13", body.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU"))
        assertTrue("must read the real grant state", body.contains("checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED"))
    }

    @Test
    fun permissionIsRequestedOnceWithDedicatedRequestCode() {
        assertTrue("dedicated request code must not collide with SAF or backup codes", Regex("postNotificationsRequestCode\\s*=\\s*1704").containsMatchIn(source))
        val body = member("private fun requestPostNotificationsOnce()")
        val guard = body.indexOf("if (postNotificationsRequested)")
        val flagSet = body.indexOf("postNotificationsRequested = true")
        val req = body.indexOf("requestPermissions(")
        assertTrue("first-attempt guard must exist", guard >= 0)
        assertTrue("guard must precede the flag", flagSet > guard)
        assertTrue("flag must precede the request", req > flagSet)
        assertTrue(
            "only POST_NOTIFICATIONS may be requested, with the dedicated code",
            Regex("requestPermissions\\s*\\(\\s*arrayOf\\s*\\(\\s*Manifest\\.permission\\.POST_NOTIFICATIONS\\s*,?\\s*\\)\\s*,\\s*postNotificationsRequestCode\\s*,?\\s*\\)").containsMatchIn(body),
        )
    }

    @Test
    fun denialIsHonoredWithoutRepromptOrLogging() {
        val body = member("override fun onRequestPermissionsResult(")
        assertTrue("ignore unrelated permission results", body.contains("if (requestCode != postNotificationsRequestCode) return"))
        assertTrue("grant result must be inspected", body.contains("grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED"))
        assertTrue("queued start must be cleared either way", body.contains("pendingRecordingStart = null"))
        assertFalse("no repeated prompt loop", body.contains("requestPermissions("))
        assertFalse("denial must not log permission state", body.contains("Log.") || body.contains("println("))
    }

    @Test
    fun noPermissionStateIsLoggedAnywhereOnTheFlow() {
        val signatures = listOf(
            "private fun startRecordingWithNotificationPermission(intent: Intent)",
            "private fun postNotificationsGranted(): Boolean",
            "private fun requestPostNotificationsOnce()",
            "override fun onRequestPermissionsResult(",
        )
        for (signature in signatures) {
            val body = member(signature)
            assertFalse("no logging in $signature", body.contains("Log.") || body.contains("println("))
        }
    }

    @Test
    fun classicCableRecordingIntentIsUnchanged() {
        val body = member("private fun recordFocusedProgram()")
        val tokens = listOf(
            "DvrRecordingService.ACTION_START",
            "DvrRecordingService.EXTRA_ID",
            "DvrRecordingService.EXTRA_TITLE",
            "DvrRecordingService.EXTRA_CHANNEL_ID",
            "DvrRecordingService.EXTRA_URL",
            "DvrRecordingService.EXTRA_USER_AGENT",
            "DvrRecordingService.EXTRA_REFERER",
            "DvrRecordingService.EXTRA_END_EPOCH_SECONDS",
            "showStatus(\"Recording: \" + program.title)",
        )
        for (token in tokens) {
            assertTrue("classic cable/multiview recording contract lost: $token", body.contains(token))
        }
    }
}

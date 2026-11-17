package com.devtunasoft.tuneros

import android.content.Context
import com.devtunasoft.tuneros.core.ParentalAttemptLimit
import com.devtunasoft.tuneros.core.ParentalPolicy
import java.io.File

/**
 * Debug-only parental controls check on a scratch preferences file, never the real one. It sets a PIN, reopens the store as
 * after a restart, counts wrong entries until the gate locks, and checks that the lock survives a restart and ends at its
 * time. Then it checks the restricted-group decision. Each check writes one PASS or FAIL line to the parental report.
 */
internal object ParentalQa {
    private const val SCRATCH_PREFS = "tuneros-parental-qa"
    private const val PIN = "1234"
    private const val WRONG = "0000"

    fun run(context: Context) {
        val report = File(context.filesDir, "tuneros-parental-report.txt")
        report.writeText("RUNNING\n")
        val scratch = context.applicationContext.getSharedPreferences(SCRATCH_PREFS, Context.MODE_PRIVATE)
        scratch.edit().clear().apply()
        try {
            val first = ParentalControlsStore(context, SCRATCH_PREFS)
            first.setPin(PIN)
            val stored = scratch.all.values.joinToString(" ") { it.toString() }
            record(report, "qa-parental-pin-stored", !stored.contains(PIN) && first.hasPin(), "only a salted hash is stored")

            val reopened = ParentalControlsStore(context, SCRATCH_PREFS)
            val afterRestart = reopened.verify(PIN, nowMillis = 1_000L)
            record(
                report,
                "qa-parental-pin-restart",
                afterRestart == ParentalAttemptLimit.Outcome.Accepted,
                "right PIN after reopening: $afterRestart",
            )

            val rejected = listOf(2_000L, 3_000L, 4_000L, 5_000L).map { now -> reopened.verify(WRONG, nowMillis = now) }
            val expectedRejections = listOf(4, 3, 2, 1).map { ParentalAttemptLimit.Outcome.Rejected(it) }
            record(report, "qa-parental-wrong-pin", rejected == expectedRejections, "attempts left: $rejected")

            val locking = ParentalControlsStore(context, SCRATCH_PREFS).verify(WRONG, nowMillis = 6_000L)
            record(
                report,
                "qa-parental-lockout",
                locking == ParentalAttemptLimit.Outcome.Locked(66_000L),
                "fifth wrong entry: $locking",
            )

            val lockedAfterRestart = ParentalControlsStore(context, SCRATCH_PREFS).verify(PIN, nowMillis = 7_000L)
            record(
                report,
                "qa-parental-lock-restart",
                lockedAfterRestart == ParentalAttemptLimit.Outcome.Locked(66_000L),
                "right PIN during the lock: $lockedAfterRestart",
            )

            val afterLock = ParentalControlsStore(context, SCRATCH_PREFS).verify(PIN, nowMillis = 66_000L)
            record(report, "qa-parental-lock-expiry", afterLock == ParentalAttemptLimit.Outcome.Accepted, "after the lock: $afterLock")

            reopened.restrict("Adult", restricted = true)
            val restricted = ParentalControlsStore(context, SCRATCH_PREFS).restrictedGroups()
            val policyHolds = ParentalPolicy.requiresPin("adult", restricted, unlocked = false) &&
                !ParentalPolicy.requiresPin("Sports", restricted, unlocked = false) &&
                !ParentalPolicy.requiresPin("adult", restricted, unlocked = true)
            record(report, "qa-parental-policy", policyHolds, "restricted groups persist: ${restricted.size}")

            val recordingAsksForPin = ParentalPolicy.requiresPinForRecording(
                channelGroup = "Adult", channelKnown = true, restrictedGroups = restricted, unlocked = false,
            )
            val unknownAsksForPin = ParentalPolicy.requiresPinForRecording(
                channelGroup = null, channelKnown = false, restrictedGroups = restricted, unlocked = false,
            )
            val unlockedPlays = !ParentalPolicy.requiresPinForRecording(
                channelGroup = "Adult", channelKnown = true, restrictedGroups = restricted, unlocked = true,
            )
            record(
                report,
                "qa-parental-recording-gate",
                recordingAsksForPin && unknownAsksForPin && unlockedPlays,
                "restricted: $recordingAsksForPin, unknown channel: $unknownAsksForPin, unlocked: $unlockedPlays",
            )
        } catch (error: Exception) {
            record(report, "qa-parental-error", false, error.javaClass.simpleName)
        } finally {
            context.applicationContext.deleteSharedPreferences(SCRATCH_PREFS)
        }
    }

    private fun record(report: File, checkId: String, passed: Boolean, detail: String) {
        report.appendText((if (passed) "PASS" else "FAIL") + "\t" + checkId + "\t" + detail + "\n")
    }
}

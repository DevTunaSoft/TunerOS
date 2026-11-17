package com.devtunasoft.tuneros

import android.content.Context
import com.devtunasoft.tuneros.core.ParentalAttemptLimit
import java.io.File

/**
 * Debug-only upgrade check. An isolated CI launch runs it after the base build was replaced in place with `adb install -r`.
 * The CI script stored a parental PIN record before the install, and this check reads it through the app's own store. It
 * writes one PASS or FAIL line to tuneros-upgrade-report.txt. It never writes the PIN and never logs it.
 */
internal object UpgradeQa {
    /** The CI test PIN. scripts/emulator-self-test.sh writes the matching salted hash, so the two must stay equal. */
    const val PIN = "1234"

    fun run(context: Context) {
        val report = File(context.filesDir, "tuneros-upgrade-report.txt")
        report.writeText("RUNNING\n")
        val (passed, detail) = try {
            when (ParentalControlsStore(context).verify(PIN)) {
                ParentalAttemptLimit.Outcome.Accepted -> true to "the PIN saved before the upgrade is accepted"
                is ParentalAttemptLimit.Outcome.Rejected -> false to "the PIN saved before the upgrade is refused"
                is ParentalAttemptLimit.Outcome.Locked -> false to "the PIN saved before the upgrade is locked"
                null -> false to "no PIN is saved"
            }
        } catch (error: Exception) {
            false to error.javaClass.simpleName
        }
        report.appendText((if (passed) "PASS" else "FAIL") + "\tqa-upgrade-pin\t" + detail + "\n")
    }
}

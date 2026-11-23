package com.devtunasoft.tuneros

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * DVR rearm after an in-place app upgrade. The process that owned the exact
 * alarms is gone by the time MY_PACKAGE_REPLACED is delivered, so pending
 * schedules must be re-armed without waiting for an app relaunch.
 *
 * This receiver is registered only for MY_PACKAGE_REPLACED. The existing
 * boot/permission receiver remains in RecordingScheduler.kt (owned by another
 * worker) and is not registered here, so each trigger has exactly one path.
 * Row filtering, permission handling and duplicate suppression stay in
 * RecordingScheduler.rearmPending; this class only dispatches one call.
 */
class UpgradeRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread({
            try {
                RecordingScheduler.rearmPending(context.applicationContext)
            } finally {
                pending.finish()
            }
        }, "TunerOS-DVR-UpgradeRearm").start()
    }
}

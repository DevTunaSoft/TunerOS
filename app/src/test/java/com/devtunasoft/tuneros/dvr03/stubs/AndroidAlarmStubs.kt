// DVR-03 alarm platform stub. Mimics Android's PendingIntent identity: a new
// registration for the same schedule id replaces the old one rather than adding
// a second alarm. Every record is synthetic.
package android.app

import android.content.Context
import android.content.Intent

open class AlarmManager {
    data class ExactAlarm(val type: Int, val triggerAtMillis: Long, val scheduleId: String)

    val exactAlarms = ArrayList<ExactAlarm>()
    var armCalls = 0
        private set
    var exactPermissionAllowed = true

    /** Optional deterministic pause used by the overlapping-rearm regression. */
    var armPauseMillis: Long = 0

    fun canScheduleExactAlarms(): Boolean = exactPermissionAllowed

    fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, intent: PendingIntent) {
        armCalls++
        if (armPauseMillis > 0) Thread.sleep(armPauseMillis)
        exactAlarms.removeAll { it.scheduleId == intent.scheduleId }
        exactAlarms += ExactAlarm(type, triggerAtMillis, intent.scheduleId)
    }

    fun cancel(intent: PendingIntent) {
        exactAlarms.removeAll { it.scheduleId == intent.scheduleId }
    }

    companion object {
        const val RTC_WAKEUP = 0
        const val ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

class PendingIntent private constructor(val scheduleId: String, val requestCode: Int) {
    override fun equals(other: Any?): Boolean =
        other is PendingIntent && scheduleId == other.scheduleId && requestCode == other.requestCode

    override fun hashCode(): Int = 31 * scheduleId.hashCode() + requestCode

    companion object {
        const val FLAG_UPDATE_CURRENT = 134217728
        const val FLAG_IMMUTABLE = 67108864

        @JvmStatic
        fun getBroadcast(
            context: Context,
            requestCode: Int,
            intent: Intent,
            flags: Int,
        ): PendingIntent = PendingIntent(intent.getStringExtra("schedule_id") ?: "", requestCode)
    }
}

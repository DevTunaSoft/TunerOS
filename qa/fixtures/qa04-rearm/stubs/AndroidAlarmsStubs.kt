// QA-04 JVM harness stubs: alarm, receiver and build platforms. Synthetic only.
package android.app

import android.content.Context
import android.content.Intent

open class AlarmManager {
    data class ExactAlarm(val type: Int, val triggerAtMillis: Long, val scheduleId: String)

    /** The reference alarm register used by the QA assertions. */
    val exactAlarms = ArrayList<ExactAlarm>()

    var exactPermissionAllowed: Boolean =
        System.getProperty("qa04.fault.permissionDenied") != "true"

    private val dropAlarms = System.getProperty("qa04.fault.dropAlarm") == "true"

    fun canScheduleExactAlarms(): Boolean = exactPermissionAllowed

    fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, intent: PendingIntent) {
        // Negative control: a store that claims an exact alarm was armed while the
        // platform silently dropped it must be caught by the rearm assertions.
        if (dropAlarms) return
        exactAlarms += ExactAlarm(type, triggerAtMillis, intent.scheduleId)
    }

    fun cancel(intent: PendingIntent) {
        exactAlarms.removeAll { it.scheduleId == intent.scheduleId }
    }

    companion object {
        const val RTC_WAKEUP = 0x00000002
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

        fun getBroadcast(
            context: Context,
            requestCode: Int,
            intent: Intent,
            flags: Int,
        ): PendingIntent = PendingIntent(intent.getStringExtra("schedule_id") ?: "", requestCode)
    }
}

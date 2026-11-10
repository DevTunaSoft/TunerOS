package com.devtunasoft.tuneros

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

internal object RecordingScheduler {
    enum class Mode { EXACT, INEXACT }

    fun schedule(context: Context, entry: RecordingScheduleEntry): Mode {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAtMillis = entry.startEpochSeconds * 1000
        val pendingIntent = alarmIntent(context, entry.id)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
        RecordingScheduleStore(context).use {
            it.updateAlarmMode(
                entry.id,
                exact = exactAllowed,
                lastError = if (exactAllowed) null else "Exact alarm access not granted; start may be delayed",
            )
        }
        return if (exactAllowed) Mode.EXACT else Mode.INEXACT
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
    }

    fun rearmPending(context: Context) {
        val now = System.currentTimeMillis() / 1000
        val entries = RecordingScheduleStore(context).use { it.pendingAfter(now) }
        for (entry in entries) schedule(context, entry)
    }

    private fun alarmIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, RecordingAlarmReceiver::class.java).apply {
            action = RecordingAlarmReceiver.ACTION_START_SCHEDULED
            data = Uri.Builder()
                .scheme("tuneros")
                .authority("recording")
                .appendPath(id)
                .build()
            putExtra(RecordingAlarmReceiver.EXTRA_SCHEDULE_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START_SCHEDULED = "com.devtunasoft.tuneros.dvr.START_SCHEDULED"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_START_SCHEDULED) return
        val id = intent.getStringExtra(EXTRA_SCHEDULE_ID)?.takeIf { it.isNotBlank() } ?: return
        val pending = goAsync()
        Thread({
            try {
                startScheduled(context.applicationContext, id)
            } finally {
                pending.finish()
            }
        }, "TunerOS-DVR-Alarm").start()
    }

    private fun startScheduled(context: Context, id: String) {
        val store = RecordingScheduleStore(context)
        val entry = store.use { it.get(id) } ?: return
        if (entry.state != "SCHEDULED") return

        val now = System.currentTimeMillis() / 1000
        if (entry.endEpochSeconds <= now) {
            RecordingScheduleStore(context).use { it.markFailed(id, "Scheduled recording window already ended") }
            SecureSourceStore(context).delete(entry.secureSourceKey)
            return
        }

        val source = SecureSourceStore(context).load(entry.secureSourceKey)
        if (source.isNullOrBlank()) {
            RecordingScheduleStore(context).use { it.markFailed(id, "Secure recording source unavailable") }
            return
        }

        val serviceIntent = Intent(context, DvrRecordingService::class.java).apply {
            action = DvrRecordingService.ACTION_START
            putExtra(DvrRecordingService.EXTRA_ID, entry.id)
            putExtra(DvrRecordingService.EXTRA_TITLE, entry.title)
            putExtra(DvrRecordingService.EXTRA_CHANNEL_ID, entry.channelId)
            putExtra(DvrRecordingService.EXTRA_URL, source)
            putExtra(DvrRecordingService.EXTRA_END_EPOCH_SECONDS, entry.endEpochSeconds)
        }

        runCatching {
            context.startForegroundService(serviceIntent)
        }.onSuccess {
            RecordingScheduleStore(context).use { it.markStarted(id) }
            SecureSourceStore(context).delete(entry.secureSourceKey)
        }.onFailure { error ->
            RecordingScheduleStore(context).use {
                it.markFailed(id, error.javaClass.simpleName)
            }
        }
    }
}

class RecordingRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) return

        val pending = goAsync()
        Thread({
            try {
                if (action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                ) return@Thread
                RecordingScheduler.rearmPending(context.applicationContext)
            } finally {
                pending.finish()
            }
        }, "TunerOS-DVR-Rearm").start()
    }
}

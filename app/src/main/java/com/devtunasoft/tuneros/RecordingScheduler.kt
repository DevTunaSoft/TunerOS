package com.devtunasoft.tuneros

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

internal object RecordingScheduler {
    enum class Mode { EXACT, PERMISSION_REQUIRED }

    // The recovery must run once on a fresh app process before the Activity
    // can queue a new preparation on its existing serial worker.
    private val startupRecoveryClaimed = AtomicBoolean(false)
    private const val PREPARATION_RECOVERY_LIMIT = 256
    private const val PREPARATION_PAGE_SIZE = 32

    /**
     * Called only from MainActivity's existing worker at process startup.
     * A process death cannot leave a PREPARING reservation blocking rebooking
     * forever. Never scan an unbounded schedule history or add a timer.
     */
    fun recoverAbandonedPreparations(context: Context): Int {
        if (!startupRecoveryClaimed.compareAndSet(false, true)) return 0
        return runCatching {
            val secrets = SecureSourceStore(context.applicationContext)
            val recovered = RecordingScheduleStore(context).use { store ->
                reclaimAbandonedPreparations(store,
                    deleteSecrets = { key -> ScheduledRecordingSecrets.delete(secrets, key) },
                )
            }
            if (recovered > 0) runCatching {
                OnDeviceSelfTest.observe(
                    context.applicationContext,
                    OnDeviceSelfTest.Event.DVR_PREPARATIONS_RECOVERED,
                    recovered.toLong(),
                )
            }
            recovered
        }.getOrDefault(0)
    }

    /**
     * Bounded, fault-injectable core of startup recovery. A failed secret
     * deletion leaves a RECOVERING row reserved; retry on next process start.
     * The same code is exercised with an isolated SQLite DB in Android QA.
     */
    internal fun reclaimAbandonedPreparations(
        store: RecordingScheduleStore,
        deleteSecrets: (String) -> Boolean,
        maxEntries: Int = PREPARATION_RECOVERY_LIMIT,
    ): Int {
        require(maxEntries in 1..PREPARATION_RECOVERY_LIMIT)
        var scanned = 0
        var recovered = 0
        var afterId: String? = null
        while (scanned < maxEntries) {
            val batch = store.orphanedPreparationsAfter(
                afterId, minOf(PREPARATION_PAGE_SIZE, maxEntries - scanned),
            )
            if (batch.isEmpty()) break
            for (entry in batch) {
                afterId = entry.id
                scanned++
                val claimed = runCatching {
                    store.markRecoveringIfOrphaned(entry.id, entry.secureSourceKey)
                }.getOrDefault(false)
                if (!claimed) continue
                val cleared = runCatching { deleteSecrets(entry.secureSourceKey) }
                    .getOrDefault(false)
                if (cleared && runCatching {
                    store.deleteRecovered(entry.id, entry.secureSourceKey)
                }.getOrDefault(false)) recovered++
            }
        }
        return recovered
    }

    fun schedule(context: Context, entry: RecordingScheduleEntry): Mode =
        RecordingScheduleStore(context).use { store -> schedule(context, entry, store) }

    private fun schedule(
        context: Context,
        entry: RecordingScheduleEntry,
        store: RecordingScheduleStore,
    ): Mode {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        // Do not allocate a PendingIntent if permission is missing.
        if (exactAllowed) {
            val triggerAtMillis = maxOf(
                entry.startEpochSeconds * 1_000,
                System.currentTimeMillis() + 1_000,
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                alarmIntent(context, entry.id),
            )
        }
        store.updateAlarmMode(
            entry.id,
            exact = exactAllowed,
            lastError = if (exactAllowed) null else
                "Exact alarm access required before this recording can be armed",
        )
        return if (exactAllowed) Mode.EXACT else Mode.PERMISSION_REQUIRED
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
    }

    /** True only when at least one pending recording was actually visited. */
    fun rearmPending(context: Context): Boolean {
        val now = System.currentTimeMillis() / 1000
        var visited = false
        var exact = 0L
        var blocked = 0L
        var failed = 0L
        try {
            RecordingScheduleStore(context).use { store ->
                var after: RecordingScheduleEntry? = null
                while (true) {
                    val batch = store.pendingAfter(now, after = after)
                    if (batch.isEmpty()) break
                    visited = true
                    for (entry in batch) {
                        // One invalid alarm must not prevent other entries being re-armed.
                        try {
                            when (schedule(context, entry, store)) {
                                Mode.EXACT -> exact++
                                Mode.PERMISSION_REQUIRED -> blocked++
                            }
                        } catch (error: Exception) {
                            failed++
                            runCatching {
                                store.updateAlarmMode(
                                    entry.id,
                                    exact = false,
                                    lastError = "Alarm re-arm failed: " + error.javaClass.simpleName,
                                )
                            }
                        }
                    }
                    after = batch.last()
                }
            }
        } finally {
            // Report only nonzero findings. No polling, extra threads, identifiers,
            // stream URLs or new long-lived state. Telemetry never blocks alarms.
            fun emit(event: OnDeviceSelfTest.Event, count: Long) {
                if (count > 0) runCatching {
                    OnDeviceSelfTest.observe(context.applicationContext, event, count)
                }
            }
            emit(OnDeviceSelfTest.Event.DVR_REARM_EXACT_COUNT, exact)
            emit(OnDeviceSelfTest.Event.DVR_REARM_BLOCKED_COUNT, blocked)
            emit(OnDeviceSelfTest.Event.DVR_REARM_FAILURE_COUNT, failed)
        }
        return visited
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
                runCatching {
                    OnDeviceSelfTest.observe(
                        context.applicationContext, OnDeviceSelfTest.Event.DVR_ALARM_DELIVERED,
                    )
                }
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
        val secrets = SecureSourceStore(context)
        if (entry.endEpochSeconds <= now) {
            failPending(context, entry, secrets, "Scheduled recording window already ended")
            return
        }

        val source = ScheduledRecordingSecrets.load(secrets, entry.secureSourceKey)
        if (source == null) {
            failPending(context, entry, secrets, "Secure recording source unavailable")
            return
        }

        val serviceIntent = Intent(context, DvrRecordingService::class.java).apply {
            action = DvrRecordingService.ACTION_START
            putExtra(DvrRecordingService.EXTRA_ID, entry.id)
            putExtra(DvrRecordingService.EXTRA_SCHEDULE_KEY, entry.secureSourceKey)
            putExtra(DvrRecordingService.EXTRA_TITLE, entry.title)
            putExtra(DvrRecordingService.EXTRA_CHANNEL_ID, entry.channelId)
            putExtra(DvrRecordingService.EXTRA_URL, source.url)
            source.userAgent?.let { putExtra(DvrRecordingService.EXTRA_USER_AGENT, it) }
            source.referer?.let { putExtra(DvrRecordingService.EXTRA_REFERER, it) }
            putExtra(DvrRecordingService.EXTRA_END_EPOCH_SECONDS, entry.endEpochSeconds)
        }

        runCatching {
            context.startForegroundService(serviceIntent)
        }.onSuccess {
            runCatching {
                OnDeviceSelfTest.observe(
                    context.applicationContext,
                    OnDeviceSelfTest.Event.DVR_SCHEDULED_SERVICE_REQUESTED,
                )
            }
            // Dispatch is not acceptance: the foreground recorder acknowledges
            // STARTED and deletes source secrets after it actually starts.
        }.onFailure { error ->
            failPending(context, entry, secrets, error.javaClass.simpleName)
        }
    }

    /**
     * A late failed alarm must not alter a newer/cancelled schedule or delete
     * another schedule's encrypted provider source. Exactly one matching
     * SCHEDULED row may transition to FAILED.
     */
    private fun failPending(
        context: Context,
        entry: RecordingScheduleEntry,
        secrets: SecureSourceStore,
        reason: String,
    ) {
        val updated = runCatching {
            RecordingScheduleStore(context).use {
                it.markFailedIfScheduled(entry.id, entry.secureSourceKey, reason)
            }
        }.getOrDefault(false)
        if (updated) runCatching {
            ScheduledRecordingSecrets.delete(secrets, entry.secureSourceKey)
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
                val shouldRearm =
                    action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ||
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                if (shouldRearm) RecordingScheduler.rearmPending(context.applicationContext)
            } finally {
                pending.finish()
            }
        }, "TunerOS-DVR-Rearm").start()
    }
}

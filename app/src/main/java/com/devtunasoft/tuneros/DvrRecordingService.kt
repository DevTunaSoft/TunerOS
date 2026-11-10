package com.devtunasoft.tuneros

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.StreamRecorder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-capture foreground DVR service.
 *
 * Media bytes are never decoded and never accumulated in memory: network input is copied
 * through StreamRecorder's bounded buffer directly to app-managed storage.
 */
class DvrRecordingService : Service() {
    companion object {
        const val ACTION_START = "com.devtunasoft.tuneros.dvr.START"
        const val ACTION_STOP = "com.devtunasoft.tuneros.dvr.STOP"
        const val EXTRA_ID = "recording_id"
        const val EXTRA_TITLE = "recording_title"
        const val EXTRA_URL = "recording_url"
        const val EXTRA_END_EPOCH_SECONDS = "recording_end_epoch_seconds"
        private const val CHANNEL_ID = "tuneros_recording"
        private const val NOTIFICATION_ID = 4001
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelled.set(true)
                return START_NOT_STICKY
            }
            ACTION_START -> startRecording(intent, startId)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent, startId: Int) {
        if (!active.compareAndSet(false, true)) {
            stopSelf(startId)
            return
        }
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: run {
            active.set(false)
            stopSelf(startId)
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Recording"
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isHttpUrl) ?: run {
            active.set(false)
            stopSelf(startId)
            return
        }
        val endEpochSeconds = intent.getLongExtra(EXTRA_END_EPOCH_SECONDS, Long.MAX_VALUE)
        cancelled.set(false)
        startForeground(
            NOTIFICATION_ID,
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.devtunasoft.tuneros.R.drawable.tuneros_icon)
                .setContentTitle("Recording")
                .setContentText(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
        worker.execute {
            try {
                capture(id, url, endEpochSeconds)
            } finally {
                active.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun capture(id: String, streamUrl: String, endEpochSeconds: Long) {
        val directory = (getExternalFilesDir("recordings") ?: File(filesDir, "recordings")).apply { mkdirs() }
        if (!directory.isDirectory) return
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        val partial = File(directory, "$safeId.partial")
        val completed = File(directory, "$safeId.ts")
        var connection: HttpURLConnection? = null
        try {
            connection = openStream(streamUrl)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val recorder = StreamRecorder()
                    val result = recorder.copy(
                        input = input,
                        output = output,
                        freeBytes = { directory.usableSpace },
                        cancelled = {
                            cancelled.get() || System.currentTimeMillis() / 1000 >= endEpochSeconds
                        },
                    )
                    output.flush()
                    val endedOnSchedule = System.currentTimeMillis() / 1000 >= endEpochSeconds
                    if (result.stopReason == StreamRecorder.StopReason.END_OF_STREAM || endedOnSchedule) {
                        if (completed.exists()) completed.delete()
                        partial.renameTo(completed)
                    }
                }
            }
        } catch (_: Exception) {
            // Keep .partial for diagnostics/recovery; structured catalog status is added next.
        } finally {
            connection?.disconnect()
        }
    }

    private fun openStream(value: String): HttpURLConnection {
        var current = URL(value)
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "TunerOS/0.1")
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: error("Redirect without Location")
            val next = URL(current, location)
            if (current.protocol == "https" && next.protocol != "https") {
                connection.disconnect()
                error("HTTPS downgrade blocked")
            }
            connection.disconnect()
            current = next
        }
        error("Too many redirects")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DVR recordings", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        (url.protocol == "http" || url.protocol == "https") && !url.host.isNullOrBlank()
    }.getOrDefault(false)

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

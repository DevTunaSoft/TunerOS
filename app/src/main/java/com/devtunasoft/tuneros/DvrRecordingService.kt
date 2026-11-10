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
import java.io.PushbackInputStream
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
        const val EXTRA_CHANNEL_ID = "recording_channel_id"
        const val EXTRA_URL = "recording_url"
        const val EXTRA_END_EPOCH_SECONDS = "recording_end_epoch_seconds"
        private const val CHANNEL_ID = "tuneros_recording"
        private const val NOTIFICATION_ID = 4001
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val catalogLazy = lazy { RecordingCatalog(this) }
    private val catalog: RecordingCatalog get() = catalogLazy.value

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        catalog.markInterruptedRecordings(System.currentTimeMillis() / 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelled.set(true)
                if (!active.get()) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> startRecording(intent, startId)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent, startId: Int) {
        if (!active.compareAndSet(false, true)) return
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: run {
            active.set(false)
            stopSelf(startId)
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Recording"
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)?.takeIf { it.isNotBlank() } ?: "unknown"
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isRawRecordableUrl) ?: run {
            active.set(false)
            stopSelf(startId)
            return
        }
        val endEpochSeconds = intent.getLongExtra(EXTRA_END_EPOCH_SECONDS, Long.MAX_VALUE)
        cancelled.set(false)
        catalog.upsertStart(id, title, channelId, System.currentTimeMillis() / 1000)
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
        if (!directory.isDirectory) {
            catalog.finish(
                id = id,
                state = "FAILED",
                stoppedEpochSeconds = System.currentTimeMillis() / 1000,
                bytesWritten = 0,
                filePath = null,
                failureReason = "Recording storage unavailable",
            )
            return
        }
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        val partial = File(directory, "$safeId.partial")
        val completed = File(directory, "$safeId${recordingExtension(streamUrl)}")
        catalog.setFilePath(id, partial.absolutePath)
        var connection: HttpURLConnection? = null
        var bytesWritten = 0L
        var lastCatalogBytes = 0L
        try {
            connection = openStream(streamUrl)
            connection.inputStream.use { rawInput ->
                val input = PushbackInputStream(rawInput, 512)
                val manifestType = detectUnsupportedManifest(connection, input)
                if (manifestType != null) {
                    catalog.finish(
                        id = id,
                        state = "FAILED",
                        stoppedEpochSeconds = System.currentTimeMillis() / 1000,
                        bytesWritten = 0,
                        filePath = null,
                        failureReason = "$manifestType recording not implemented",
                    )
                    return
                }
                partial.outputStream().use { output ->
                    val recorder = StreamRecorder()
                    val result = recorder.copy(
                        input = input,
                        output = output,
                        freeBytes = { directory.usableSpace },
                        cancelled = {
                            cancelled.get() || System.currentTimeMillis() / 1000 >= endEpochSeconds
                        },
                        onProgress = { total ->
                            bytesWritten = total
                            if (total - lastCatalogBytes >= 4L * 1024 * 1024) {
                                catalog.updateProgress(id, total)
                                lastCatalogBytes = total
                            }
                        },
                    )
                    output.flush()
                    bytesWritten = result.bytesWritten
                    val endedOnSchedule = System.currentTimeMillis() / 1000 >= endEpochSeconds
                    if (result.stopReason == StreamRecorder.StopReason.END_OF_STREAM || endedOnSchedule) {
                        if (completed.exists()) completed.delete()
                        val finalized = partial.renameTo(completed)
                        catalog.finish(
                            id = id,
                            state = if (finalized) "COMPLETED" else "PARTIAL",
                            stoppedEpochSeconds = System.currentTimeMillis() / 1000,
                            bytesWritten = bytesWritten,
                            filePath = (if (finalized) completed else partial).absolutePath,
                            failureReason = if (finalized) null else "Unable to finalize recording file",
                        )
                    } else {
                        val state = if (result.stopReason == StreamRecorder.StopReason.CANCELLED) "CANCELLED" else "PARTIAL"
                        catalog.finish(
                            id, state, System.currentTimeMillis() / 1000, bytesWritten,
                            partial.takeIf { it.exists() }?.absolutePath,
                            if (result.stopReason == StreamRecorder.StopReason.LOW_STORAGE) "Storage reserve reached" else null,
                        )
                    }
                }
            }
        } catch (error: Exception) {
            catalog.finish(
                id = id,
                state = "FAILED",
                stoppedEpochSeconds = System.currentTimeMillis() / 1000,
                bytesWritten = bytesWritten,
                filePath = partial.takeIf { it.exists() }?.absolutePath,
                failureReason = error.javaClass.simpleName,
            )
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


    private fun detectUnsupportedManifest(
        connection: HttpURLConnection,
        input: PushbackInputStream,
    ): String? {
        val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (contentType in setOf(
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl",
            )
        ) return "HLS"
        if (contentType == "application/dash+xml") return "DASH"

        val probe = ByteArray(512)
        val count = input.read(probe)
        if (count > 0) input.unread(probe, 0, count)
        if (count <= 0) return null
        val text = String(probe, 0, count, Charsets.UTF_8).trimStart()
        if (text.startsWith("#EXTM3U")) return "HLS"
        if (text.startsWith("<?xml") && text.contains("<MPD", ignoreCase = true)) return "DASH"
        if (text.startsWith("<MPD", ignoreCase = true)) return "DASH"
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DVR recordings", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun isRawRecordableUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        val path = url.path.lowercase()
        (url.protocol == "http" || url.protocol == "https") &&
            !url.host.isNullOrBlank() &&
            !path.endsWith(".m3u8") &&
            !path.endsWith(".mpd")
    }.getOrDefault(false)

    private fun recordingExtension(value: String): String = runCatching {
        when (URL(value).path.substringAfterLast('.', "").lowercase()) {
            "mp4" -> ".mp4"
            "mkv" -> ".mkv"
            "webm" -> ".webm"
            "m2ts" -> ".m2ts"
            else -> ".ts"
        }
    }.getOrDefault(".ts")

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdownNow()
        if (catalogLazy.isInitialized()) catalog.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package com.devtunasoft.tuneros

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsCaptureEngine
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.StreamRecorder
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-capture foreground DVR service.
 *
 * Raw streams and supported HLS segments are copied directly to storage through bounded
 * buffers. The service never decodes video and never accumulates recording media in RAM.
 */
class DvrRecordingService : Service() {
    companion object {
        const val ACTION_START = "com.devtunasoft.tuneros.dvr.START"
        const val ACTION_STOP = "com.devtunasoft.tuneros.dvr.STOP"
        const val EXTRA_ID = "recording_id"
        const val EXTRA_TITLE = "recording_title"
        const val EXTRA_CHANNEL_ID = "recording_channel_id"
        const val EXTRA_URL = "recording_url"
        const val EXTRA_USER_AGENT = "recording_user_agent"
        const val EXTRA_REFERER = "recording_referer"
        const val EXTRA_END_EPOCH_SECONDS = "recording_end_epoch_seconds"

        private const val CHANNEL_ID = "tuneros_recording"
        private const val NOTIFICATION_ID = 4001
        private const val CATALOG_PROGRESS_BYTES = 4L * 1024 * 1024
    }

    private data class CaptureOutcome(
        val state: String,
        val bytesWritten: Long,
        val finalizeFile: Boolean,
        val failureReason: String? = null,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val catalogLazy = lazy { RecordingCatalog(this) }
    private val catalog: RecordingCatalog get() = catalogLazy.value

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        catalog.markInterruptedRecordings(nowSeconds())
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
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Recording"
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)?.takeIf { it.isNotBlank() } ?: "unknown"
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isRecordableUrl) ?: return
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }?.take(512)
        val referer = intent.getStringExtra(EXTRA_REFERER)?.takeIf(::isHttpUrl)?.take(2048)

        if (!active.compareAndSet(false, true)) {
            catalog.upsertStart(id, title, channelId, nowSeconds())
            catalog.finish(id, "FAILED", nowSeconds(), 0, null, "Recorder busy")
            return
        }

        val endEpochSeconds = intent.getLongExtra(EXTRA_END_EPOCH_SECONDS, Long.MAX_VALUE)
        cancelled.set(false)
        catalog.upsertStart(id, title, channelId, nowSeconds())
        startForeground(
            NOTIFICATION_ID,
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.tuneros_icon)
                .setContentTitle("Recording")
                .setContentText(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )

        worker.execute {
            try {
                capture(id, url, endEpochSeconds, userAgent, referer)
            } finally {
                active.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun capture(
        id: String,
        streamUrl: String,
        endEpochSeconds: Long,
        userAgent: String?,
        referer: String?,
    ) {
        val directory = (getExternalFilesDir("recordings") ?: File(filesDir, "recordings")).apply { mkdirs() }
        if (!directory.isDirectory) {
            catalog.finish(id, "FAILED", nowSeconds(), 0, null, "Recording storage unavailable")
            return
        }

        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        val partial = File(directory, safeId + ".partial")
        val completed = File(directory, safeId + recordingExtension(streamUrl))
        catalog.setFilePath(id, partial.absolutePath)

        var initialConnection: HttpURLConnection? = null
        try {
            val outcome = if (looksLikeHls(streamUrl)) {
                captureHls(
                    id, streamUrl, endEpochSeconds, userAgent, referer,
                    directory, partial,
                )
            } else {
                val connection = openHttp(streamUrl, userAgent, referer, null)
                initialConnection = connection
                val probedOutcome = connection.inputStream.use { rawInput ->
                    val input = PushbackInputStream(rawInput, 512)
                    when (detectManifestType(connection, input)) {
                        "HLS" -> null
                        "DASH" -> CaptureOutcome(
                            state = "FAILED",
                            bytesWritten = 0,
                            finalizeFile = false,
                            failureReason = "DASH recording not implemented",
                        )
                        else -> partial.outputStream().use { output ->
                            captureRaw(id, input, output, endEpochSeconds, directory)
                        }
                    }
                }
                if (probedOutcome != null) {
                    probedOutcome
                } else {
                    connection.disconnect()
                    initialConnection = null
                    captureHls(
                        id, streamUrl, endEpochSeconds, userAgent, referer,
                        directory, partial,
                    )
                }
            }
            applyOutcome(id, partial, completed, outcome)
        } catch (error: Exception) {
            val bytes = partial.takeIf { it.exists() }?.length() ?: 0L
            catalog.finish(
                id = id,
                state = if (bytes > 0) "PARTIAL" else "FAILED",
                stoppedEpochSeconds = nowSeconds(),
                bytesWritten = bytes,
                filePath = partial.takeIf { it.exists() && bytes > 0 }?.absolutePath,
                failureReason = error.javaClass.simpleName,
            )
            if (bytes == 0L) partial.delete()
        } finally {
            initialConnection?.disconnect()
        }
    }

    private fun captureRaw(
        id: String,
        input: InputStream,
        output: java.io.OutputStream,
        endEpochSeconds: Long,
        directory: File,
    ): CaptureOutcome {
        var lastCatalogBytes = 0L
        val result = StreamRecorder().copy(
            input = input,
            output = output,
            freeBytes = { directory.usableSpace },
            cancelled = { cancelled.get() || nowSeconds() >= endEpochSeconds },
            onProgress = { total ->
                if (total - lastCatalogBytes >= CATALOG_PROGRESS_BYTES) {
                    catalog.updateProgress(id, total)
                    lastCatalogBytes = total
                }
            },
        )
        output.flush()
        val deadlineReached = nowSeconds() >= endEpochSeconds && !cancelled.get()
        return when (result.stopReason) {
            StreamRecorder.StopReason.END_OF_STREAM -> {
                if (deadlineReached) CaptureOutcome("COMPLETED", result.bytesWritten, true)
                else CaptureOutcome(
                    "PARTIAL",
                    result.bytesWritten,
                    false,
                    "Stream ended before scheduled stop",
                )
            }
            StreamRecorder.StopReason.CANCELLED -> {
                if (deadlineReached) CaptureOutcome("COMPLETED", result.bytesWritten, true)
                else CaptureOutcome("CANCELLED", result.bytesWritten, false)
            }
            StreamRecorder.StopReason.LOW_STORAGE -> CaptureOutcome(
                "PARTIAL",
                result.bytesWritten,
                false,
                "Storage reserve reached",
            )
        }
    }

    private fun captureHls(
        id: String,
        streamUrl: String,
        endEpochSeconds: Long,
        userAgent: String?,
        referer: String?,
        directory: File,
        partial: File,
    ): CaptureOutcome {
        var lastCatalogBytes = 0L
        partial.outputStream().use { output ->
            val source = AndroidHlsCaptureSource(
                openConnection = { url, range ->
                    openHttp(url, userAgent, referer, range)
                },
                freeBytes = { directory.usableSpace },
            )
            val result = HlsCaptureEngine().record(
                initialUrl = streamUrl,
                output = output,
                endEpochSeconds = endEpochSeconds,
                source = source,
                cancelled = { cancelled.get() },
                nowEpochSeconds = ::nowSeconds,
                sleepMillis = ::sleepHlsPoll,
                onProgress = { total ->
                    if (total - lastCatalogBytes >= CATALOG_PROGRESS_BYTES) {
                        catalog.updateProgress(id, total)
                        lastCatalogBytes = total
                    }
                },
            )
            output.flush()
            return when (result.stopReason) {
                HlsCaptureEngine.StopReason.DEADLINE ->
                    CaptureOutcome("COMPLETED", result.bytesWritten, true)

                HlsCaptureEngine.StopReason.END_LIST -> {
                    if (nowSeconds() >= endEpochSeconds) {
                        CaptureOutcome("COMPLETED", result.bytesWritten, true)
                    } else {
                        CaptureOutcome(
                            state = if (result.bytesWritten > 0) "PARTIAL" else "FAILED",
                            bytesWritten = result.bytesWritten,
                            finalizeFile = false,
                            failureReason = "HLS stream ended before scheduled stop",
                        )
                    }
                }

                HlsCaptureEngine.StopReason.CANCELLED ->
                    CaptureOutcome("CANCELLED", result.bytesWritten, false)

                HlsCaptureEngine.StopReason.LOW_STORAGE ->
                    CaptureOutcome("PARTIAL", result.bytesWritten, false, "Storage reserve reached")

                HlsCaptureEngine.StopReason.STALLED,
                HlsCaptureEngine.StopReason.GAP,
                HlsCaptureEngine.StopReason.UNSUPPORTED -> CaptureOutcome(
                    state = if (result.bytesWritten > 0) "PARTIAL" else "FAILED",
                    bytesWritten = result.bytesWritten,
                    finalizeFile = false,
                    failureReason = result.detail ?: result.stopReason.name,
                )
            }
        }
    }

    private fun applyOutcome(
        id: String,
        partial: File,
        completed: File,
        outcome: CaptureOutcome,
    ) {
        if (outcome.finalizeFile) {
            if (completed.exists()) completed.delete()
            val finalized = partial.renameTo(completed)
            catalog.finish(
                id = id,
                state = if (finalized) "COMPLETED" else "PARTIAL",
                stoppedEpochSeconds = nowSeconds(),
                bytesWritten = outcome.bytesWritten,
                filePath = (if (finalized) completed else partial).takeIf { it.exists() }?.absolutePath,
                failureReason = if (finalized) null else "Unable to finalize recording file",
            )
            return
        }

        if (outcome.bytesWritten == 0L && outcome.state == "FAILED") partial.delete()
        catalog.finish(
            id = id,
            state = outcome.state,
            stoppedEpochSeconds = nowSeconds(),
            bytesWritten = outcome.bytesWritten,
            filePath = partial.takeIf { it.exists() && outcome.bytesWritten > 0 }?.absolutePath,
            failureReason = outcome.failureReason,
        )
    }

    private fun openHttp(
        value: String,
        userAgent: String?,
        referer: String?,
        byteRange: HlsByteRange?,
    ): HttpURLConnection {
        var current = URL(value)
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent ?: "TunerOS/0.1")
            referer?.let { connection.setRequestProperty("Referer", it) }
            byteRange?.let {
                val rangeEnd = Math.addExact(it.offset, it.length - 1)
                connection.setRequestProperty("Range", "bytes=" + it.offset + "-" + rangeEnd)
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: run {
                        connection.disconnect()
                        error("Redirect without Location")
                    }
                val next = URL(current, location)
                if (current.protocol == "https" && next.protocol != "https") {
                    connection.disconnect()
                    error("HTTPS downgrade blocked")
                }
                connection.disconnect()
                current = next
                return@repeat
            }

            if (code !in 200..299) {
                connection.disconnect()
                error("HTTP status " + code)
            }
            if (byteRange != null && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                error("Server ignored HLS byte range")
            }
            return connection
        }
        error("Too many redirects")
    }

    private fun detectManifestType(
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

    private fun sleepHlsPoll(delayMillis: Long) {
        var remaining = delayMillis
        while (remaining > 0 && !cancelled.get()) {
            val chunk = minOf(remaining, 250L)
            Thread.sleep(chunk)
            remaining -= chunk
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DVR recordings", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        (url.protocol == "http" || url.protocol == "https") && !url.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun isRecordableUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        val path = url.path.lowercase()
        (url.protocol == "http" || url.protocol == "https") &&
            !url.host.isNullOrBlank() &&
            !path.endsWith(".mpd")
    }.getOrDefault(false)

    private fun looksLikeHls(value: String): Boolean = runCatching {
        URL(value).path.lowercase().endsWith(".m3u8")
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

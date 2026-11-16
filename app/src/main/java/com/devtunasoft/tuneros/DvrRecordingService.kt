package com.devtunasoft.tuneros

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.devtunasoft.tuneros.core.AbortableSlot
import com.devtunasoft.tuneros.core.DashCaptureEngine
import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsCaptureEngine
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.RecordingState
import com.devtunasoft.tuneros.core.RecordingWorkerLifecycle
import com.devtunasoft.tuneros.core.StreamRecorder
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-capture foreground DVR service.
 *
 * Raw streams and supported HLS segments are copied directly to storage through bounded
 * buffers. The service never decodes video and never accumulates recording media in RAM.
 */
class DvrRecordingService : Service() {
    companion object {
        /** Set only by the debug QA harness so its catalog never touches recordings.db. */
        @Volatile internal var qaCatalogDatabaseName: String? = null
        const val ACTION_START = "com.devtunasoft.tuneros.dvr.START"
        const val ACTION_STOP = "com.devtunasoft.tuneros.dvr.STOP"
        const val EXTRA_ID = "recording_id"
        const val EXTRA_SCHEDULE_KEY = "recording_schedule_key"
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
    private val activeRecordingId = AtomicReference<String?>(null)
    private val connections = AbortableSlot<HttpURLConnection> { it.disconnect() }
    private val catalogLazy = lazy { RecordingCatalog(this, qaCatalogDatabaseName ?: "recordings.db") }
    private val catalog: RecordingCatalog get() = catalogLazy.value

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val recovered = catalog.markInterruptedRecordings(nowSeconds())
        if (recovered > 0) {
            runCatching {
                OnDeviceSelfTest.observe(
                    applicationContext, OnDeviceSelfTest.Event.RECORDING_RECOVERED,
                    recovered.toLong(),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelled.set(true)
                connections.abortCurrent()
                if (activeRecordingId.get() == null) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // startForegroundService() obliges the service to call startForeground() at once, even for a request
                // refused below. Otherwise Android crashes the caller. A busy service is already in the foreground.
                if (activeRecordingId.get() == null) promoteToForeground()
                startRecording(intent, startId)
            }
            else -> if (activeRecordingId.get() == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent, startId: Int) {
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() }
            ?: return rejectInvalidStart(startId)
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Recording"
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)?.takeIf { it.isNotBlank() } ?: "unknown"
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isRecordableUrl)
            ?: return rejectInvalidStart(startId)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }?.take(512)
        val referer = intent.getStringExtra(EXTRA_REFERER)?.takeIf(::isHttpUrl)?.take(2048)
        val scheduleKey = intent.getStringExtra(EXTRA_SCHEDULE_KEY)?.takeIf { it.isNotBlank() }
        if (scheduleKey != null) {
            val scheduled = runCatching {
                RecordingScheduleStore(this).use { it.get(id) }
            }.getOrNull()
            if (scheduled?.state != "SCHEDULED" || scheduled.secureSourceKey != scheduleKey) {
                return rejectInvalidStart(startId)
            }
        }

        if (!activeRecordingId.compareAndSet(null, id)) {
            if (activeRecordingId.get() != id) {
                if (catalog.tryInsertStart(id, title, channelId, nowSeconds())) {
                    catalog.finish(id, "FAILED", nowSeconds(), 0, null, "Recorder busy")
                }
                if (scheduleKey != null) {
                    failScheduledDispatch(id, scheduleKey, "Recorder busy")
                }
            }
            return
        }

        val endEpochSeconds = intent.getLongExtra(EXTRA_END_EPOCH_SECONDS, Long.MAX_VALUE)
        cancelled.set(false)
        connections.reset()
        if (!catalog.tryInsertStart(id, title, channelId, nowSeconds())) {
            if (scheduleKey != null) {
                failScheduledDispatch(id, scheduleKey, "Recording ID already exists")
            }
            activeRecordingId.compareAndSet(id, null)
            stopSelf(startId)
            return
        }
        val foregroundAccepted = runCatching {
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
        }.isSuccess
        if (!foregroundAccepted) {
            catalog.finish(id, "FAILED", nowSeconds(), 0, null, "Foreground start failed")
            if (scheduleKey != null) {
                failScheduledDispatch(id, scheduleKey, "Foreground start failed")
            }
            activeRecordingId.compareAndSet(id, null)
            stopSelf(startId)
            return
        }

        if (scheduleKey != null) {
            val accepted = runCatching {
                RecordingScheduleStore(this).use {
                    it.markStartedIfScheduled(id, scheduleKey)
                }
            }.getOrDefault(false)
            if (!accepted) {
                // Schedule could have been cancelled between alarm delivery and
                // service startup; do not record or delete another schedule's key.
                catalog.finish(id, "FAILED", nowSeconds(), 0, null, "Schedule cancelled before start")
                activeRecordingId.compareAndSet(id, null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            // Failure to clean up encrypted metadata must never interrupt capture.
            runCatching {
                ScheduledRecordingSecrets.delete(SecureSourceStore(this), scheduleKey)
            }
            runCatching {
                OnDeviceSelfTest.observe(
                    applicationContext, OnDeviceSelfTest.Event.DVR_SCHEDULED_SERVICE_ACCEPTED,
                )
            }
        }

        worker.execute {
            try {
                runCatching {
                    OnDeviceSelfTest.observe(
                        applicationContext, OnDeviceSelfTest.Event.RECORDING_STARTED,
                    )
                }
                capture(id, url, endEpochSeconds, userAgent, referer)
            } finally {
                activeRecordingId.set(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Not stopSelf(startId): a later Stop command makes that id stale and Android
                // would keep the service running after the recording ended.
                stopSelf()
            }
        }
    }

    /**
     * On a rejected scheduled dispatch, fail only the matching pending row.
     * A stale alarm must not overwrite a newer reservation or erase its key.
     */
    private fun failScheduledDispatch(id: String, sourceKey: String, reason: String) {
        val failed = runCatching {
            RecordingScheduleStore(this).use {
                it.markFailedIfScheduled(id, sourceKey, reason)
            }
        }.getOrDefault(false)
        if (failed) runCatching {
            ScheduledRecordingSecrets.delete(SecureSourceStore(this), sourceKey)
        }
    }

    private fun capture(
        id: String,
        streamUrl: String,
        endEpochSeconds: Long,
        userAgent: String?,
        referer: String?,
    ) {
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        var sink: RecordingSink? = null
        var initialConnection: HttpURLConnection? = null
        try {
            sink = RecordingSinkFactory.create(
                context = this,
                safeId = safeId,
                extension = recordingExtension(streamUrl),
            )
            catalog.setStorageLocation(id, sink.location)

            val outcome = if (looksLikeHls(streamUrl)) {
                captureHls(
                    id, streamUrl, endEpochSeconds, userAgent, referer, sink,
                )
            } else {
                val connection = openHttp(streamUrl, userAgent, referer, null)
                initialConnection = connection
                var manifestType: String? = null
                val probedOutcome = connection.inputStream.use { rawInput ->
                    val input = PushbackInputStream(rawInput, 512)
                    val detected = detectManifestType(connection, input)
                    manifestType = detected
                    when (detected) {
                        "HLS", "DASH" -> null
                        else -> sink.openOutput().use { output ->
                            captureRaw(id, input, output, endEpochSeconds, sink)
                        }
                    }
                }
                if (probedOutcome != null) {
                    probedOutcome
                } else {
                    connection.disconnect()
                    initialConnection = null
                    if (manifestType == "DASH") {
                        captureDash(id, streamUrl, endEpochSeconds, userAgent, referer, sink)
                    } else {
                        captureHls(
                            id, streamUrl, endEpochSeconds, userAgent, referer, sink,
                        )
                    }
                }
            }
            applyOutcome(id, sink, outcome)
        } catch (error: Exception) {
            val bytes = sink?.bytesWritten ?: 0L
            val wasCancelled = cancelled.get()
            val state = if (wasCancelled) "CANCELLED" else if (bytes > 0) "PARTIAL" else "FAILED"
            catalog.finish(
                id = id,
                state = state,
                stoppedEpochSeconds = nowSeconds(),
                bytesWritten = bytes,
                storageLocation = sink?.location?.takeIf { bytes > 0 },
                failureReason = if (wasCancelled) null else error.javaClass.simpleName,
            )
            if (bytes == 0L) sink?.delete()
            observeOutcome(state, bytes)
        } finally {
            initialConnection?.disconnect()
            connections.abortCurrent()
        }
    }

    private fun captureRaw(
        id: String,
        input: InputStream,
        output: java.io.OutputStream,
        endEpochSeconds: Long,
        sink: RecordingSink,
    ): CaptureOutcome {
        var lastCatalogBytes = 0L
        val result = StreamRecorder().copy(
            input = input,
            output = output,
            freeBytes = sink::freeBytes,
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
        sink: RecordingSink,
    ): CaptureOutcome {
        var lastCatalogBytes = 0L
        sink.openOutput().use { output ->
            val source = AndroidHlsCaptureSource(
                openConnection = { url, range ->
                    openHttp(url, userAgent, referer, range)
                },
                freeBytes = sink::freeBytes,
            )
            val result = HlsCaptureEngine().record(
                initialUrl = streamUrl,
                output = output,
                endEpochSeconds = endEpochSeconds,
                source = source,
                cancelled = { cancelled.get() },
                nowEpochSeconds = ::nowSeconds,
                sleepMillis = ::sleepRecordingPoll,
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

    /**
     * A capture that reached its stop without writing a byte is a failure, even at the scheduled
     * deadline. Keeping it COMPLETED would offer an empty file for playback.
     */
    private fun settledOutcome(requested: CaptureOutcome): CaptureOutcome {
        val state = RecordingPolicy.finalState(
            RecordingState.valueOf(requested.state),
            requested.bytesWritten,
        ).name
        return if (state == requested.state) {
            requested
        } else {
            requested.copy(
                state = state,
                finalizeFile = false,
                failureReason = "No bytes before scheduled stop",
            )
        }
    }

    private fun captureDash(
        id: String,
        streamUrl: String,
        endEpochSeconds: Long,
        userAgent: String?,
        referer: String?,
        sink: RecordingSink,
    ): CaptureOutcome {
        var lastCatalogBytes = 0L
        val source = AndroidDashCaptureSource(
            openConnection = { url, range -> openHttp(url, userAgent, referer, range) },
            freeBytes = sink::freeBytes,
        )
        return sink.openOutput().use { output ->
            val result = DashCaptureEngine().record(
                initialUrl = streamUrl,
                output = output,
                endEpochSeconds = endEpochSeconds,
                source = source,
                cancelled = { cancelled.get() },
                nowEpochSeconds = ::nowSeconds,
                sleepMillis = ::sleepRecordingPoll,
                onProgress = { total ->
                    if (total - lastCatalogBytes >= CATALOG_PROGRESS_BYTES) {
                        catalog.updateProgress(id, total)
                        lastCatalogBytes = total
                    }
                },
            )
            output.flush()
            val reachedStop = nowSeconds() >= endEpochSeconds
            when (result.stopReason) {
                DashCaptureEngine.StopReason.END_LIST ->
                    if (reachedStop) {
                        CaptureOutcome("COMPLETED", result.bytesWritten, true)
                    } else {
                        CaptureOutcome("PARTIAL", result.bytesWritten, false, "DASH stream ended before scheduled stop")
                    }
                DashCaptureEngine.StopReason.DEADLINE ->
                    CaptureOutcome("COMPLETED", result.bytesWritten, true)
                DashCaptureEngine.StopReason.CANCELLED ->
                    if (reachedStop && !cancelled.get()) {
                        CaptureOutcome("COMPLETED", result.bytesWritten, true)
                    } else {
                        CaptureOutcome("CANCELLED", result.bytesWritten, false)
                    }
                DashCaptureEngine.StopReason.LOW_STORAGE ->
                    CaptureOutcome("PARTIAL", result.bytesWritten, false, "Storage reserve reached")
                DashCaptureEngine.StopReason.UNSUPPORTED -> CaptureOutcome(
                    state = if (result.bytesWritten > 0) "PARTIAL" else "FAILED",
                    bytesWritten = result.bytesWritten,
                    finalizeFile = false,
                    failureReason = result.detail ?: "DASH recording is not supported",
                )
            }
        }
    }

    private fun applyOutcome(
        id: String,
        sink: RecordingSink,
        requested: CaptureOutcome,
    ) {
        val outcome = settledOutcome(requested)
        if (outcome.finalizeFile) {
            val finalizedLocation = sink.finalizeRecording()
            val state = if (finalizedLocation != null) "COMPLETED" else "PARTIAL"
            catalog.finish(
                id = id,
                state = state,
                stoppedEpochSeconds = nowSeconds(),
                bytesWritten = outcome.bytesWritten,
                storageLocation = finalizedLocation ?: sink.location.takeIf { outcome.bytesWritten > 0 },
                failureReason = if (finalizedLocation != null) null else
                    "Unable to finalize recording output",
            )
            observeOutcome(state, outcome.bytesWritten)
            return
        }

        if (outcome.bytesWritten == 0L && outcome.state == "FAILED") sink.delete()
        catalog.finish(
            id = id,
            state = outcome.state,
            stoppedEpochSeconds = nowSeconds(),
            bytesWritten = outcome.bytesWritten,
            storageLocation = sink.location.takeIf { outcome.bytesWritten > 0 },
            failureReason = outcome.failureReason,
        )
        observeOutcome(outcome.state, outcome.bytesWritten)
    }

    private fun observeOutcome(state: String, bytes: Long) {
        val event = when (state) {
            "COMPLETED" -> if (bytes > 0) OnDeviceSelfTest.Event.RECORDING_COMPLETED
                else OnDeviceSelfTest.Event.RECORDING_FAILED
            "PARTIAL" -> OnDeviceSelfTest.Event.RECORDING_PARTIAL
            "CANCELLED" -> OnDeviceSelfTest.Event.RECORDING_CANCELLED
            else -> OnDeviceSelfTest.Event.RECORDING_FAILED
        }
        // Logging is strictly best effort: it cannot prevent recording finalization.
        runCatching { OnDeviceSelfTest.observe(applicationContext, event, bytes) }
    }

    private fun openHttp(
        value: String,
        userAgent: String?,
        referer: String?,
        byteRange: HlsByteRange?,
    ): HttpURLConnection = openSourceConnection(value, userAgent, referer, byteRange, connections)

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

    private fun sleepRecordingPoll(delayMillis: Long) {
        var remaining = delayMillis
        while (remaining > 0 && !cancelled.get()) {
            val chunk = minOf(remaining, 250L)
            Thread.sleep(chunk)
            remaining -= chunk
        }
    }

    private fun promoteToForeground() {
        runCatching {
            startForeground(
                NOTIFICATION_ID,
                android.app.Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.tuneros_icon)
                    .setContentTitle("Recording")
                    .setContentText("Checking request")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        }
    }

    private fun rejectInvalidStart(startId: Int) {
        if (activeRecordingId.get() == null) stopSelf(startId)
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
        (url.protocol == "http" || url.protocol == "https") && !url.host.isNullOrBlank()
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
        connections.abortCurrent()
        // A cancelled recording may still be unwinding its stream and persisting
        // PARTIAL/FAILED. Closing SQLite here races that final catalog update.
        // Enqueue the close *after* the sole recording worker, without blocking
        // the main thread or starting another worker. Disconnect wakes reads;
        // cancellation is checked before each subsequent copy/poll.
        RecordingWorkerLifecycle.shutdownAfterPendingWork(worker) {
            if (catalogLazy.isInitialized()) catalog.close()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

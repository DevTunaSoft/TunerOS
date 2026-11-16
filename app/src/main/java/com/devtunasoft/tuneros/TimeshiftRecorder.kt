package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.AbortableSlot
import com.devtunasoft.tuneros.core.HlsCaptureEngine
import com.devtunasoft.tuneros.core.HlsSegmentListener
import com.devtunasoft.tuneros.core.TimeshiftCapture
import com.devtunasoft.tuneros.core.TimeshiftWindow
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records one live HLS channel into a private, bounded ring, so the viewer can play it a few segments behind live.
 * Recording runs on its own thread. A recorder is used once: stopping it, or a failure, deletes the whole ring, so no
 * timeshift content outlives its viewing session. A ring left behind by a process that died is discarded at start.
 */
internal class TimeshiftRecorder(
    private val directory: File,
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val userAgent: String? = null,
    private val referer: String? = null,
) {
    sealed class State {
        object Idle : State()
        object Recording : State()
        class Stopped(val reason: HlsCaptureEngine.StopReason, val detail: String?) : State()
        /** The class name of the failure only: exception messages can carry the source URL. */
        class Failed(val error: String) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    private val cancelled = AtomicBoolean(false)
    private val completed = AtomicInteger(0)
    private val connections = AbortableSlot<HttpURLConnection> { it.disconnect() }

    /** Segments recorded so far, which is what the window has been given. */
    val completedSegments: Int get() = completed.get()

    /** The playlist to give the player. It is valid while the recording is running. */
    val playlist: File get() = File(directory, AndroidTimeshiftRing.PLAYLIST_NAME)

    fun start(url: String) {
        check(state == State.Idle) { "a timeshift recorder is used once" }
        state = State.Recording
        Thread({ record(url) }, "tuneros-timeshift").apply { isDaemon = true }.start()
    }

    /**
     * Asks the recording to end. A request in flight is aborted, so this returns at once; the thread then deletes the ring.
     * The stop is reported as CANCELLED even when the abort surfaces as an exception in the thread.
     */
    fun stop() {
        cancelled.set(true)
        connections.abortCurrent()
    }

    private fun record(url: String) {
        var capture: TimeshiftCapture? = null
        var cleanupFailure: Throwable? = null
        // The outcome is published only after the ring is gone, so a reader that sees Stopped also sees no files.
        val outcome: State = try {
            directory.deleteRecursively()
            val created = TimeshiftCapture(TimeshiftWindow(maxSegments, maxBytes), AndroidTimeshiftRing(directory))
            capture = created
            val source = AndroidHlsCaptureSource(
                openConnection = { value, range ->
                    openSourceConnection(value, userAgent, referer, range, connections)
                },
                freeBytes = { directory.usableSpace },
            )
            val listener = object : HlsSegmentListener {
                override fun onSegmentComplete(
                    sequence: Long,
                    durationSeconds: Double,
                    bytes: Long,
                    discontinuity: Boolean,
                ) {
                    created.onSegmentComplete(sequence, durationSeconds, bytes, discontinuity)
                    completed.incrementAndGet()
                }
            }
            val result = HlsCaptureEngine().record(
                initialUrl = url,
                output = created.output,
                endEpochSeconds = Long.MAX_VALUE,
                source = source,
                cancelled = { cancelled.get() },
                nowEpochSeconds = { System.currentTimeMillis() / 1000L },
                sleepMillis = { Thread.sleep(it) },
                segmentListener = listener,
            )
            State.Stopped(result.stopReason, result.detail)
        } catch (error: Exception) {
            if (cancelled.get()) {
                State.Stopped(HlsCaptureEngine.StopReason.CANCELLED, null)
            } else {
                State.Failed(error.javaClass.simpleName)
            }
        } finally {
            // Best effort, and never skipped: the ring directory is removed even when a file inside it cannot be.
            cleanupFailure = runCatching { capture?.finish() }.exceptionOrNull()
            if (!directory.deleteRecursively()) {
                cleanupFailure = cleanupFailure ?: java.io.IOException("timeshift ring not removed")
            }
        }
        state = cleanupFailure?.let { State.Failed(it.javaClass.simpleName) } ?: outcome
    }

    companion object {
        const val DEFAULT_MAX_SEGMENTS = 900
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L

        /** Segments that must be listed before the player switches to the ring. */
        const val STARTUP_SEGMENTS = 3
        const val STARTUP_DEADLINE_MILLIS = 30_000L

        /** How far back a single "Back" step moves. */
        const val BACK_MILLIS = 10_000L
    }
}

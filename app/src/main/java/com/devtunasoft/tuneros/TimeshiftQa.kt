package com.devtunasoft.tuneros

import android.os.Handler
import com.devtunasoft.tuneros.core.HlsByteRange
import java.io.File
import java.net.HttpURLConnection

/**
 * Debug-only timeshift check on scratch data. It records a live HLS channel into a scratch ring with a small window, waits
 * until the window has dropped its oldest segments, plays the local playlist, then stops and checks that the ring is gone.
 * Each check writes one PASS or FAIL line to [report].
 */
internal class TimeshiftQa(
    private val report: File,
    private val ringDirectory: File,
    private val handler: Handler,
    private val openConnection: (String, HlsByteRange?) -> HttpURLConnection,
    private val playLocal: (String, (String) -> Unit) -> Unit,
) {
    fun run(url: String, maxSegments: Int) {
        report.writeText("RUNNING\n")
        val recorder = TimeshiftRecorder(ringDirectory, maxSegments, TimeshiftRecorder.DEFAULT_MAX_BYTES, openConnection)
        recorder.start(url)
        awaitFullWindow(recorder, maxSegments, System.currentTimeMillis() + RECORD_DEADLINE_MILLIS)
    }

    private fun awaitFullWindow(recorder: TimeshiftRecorder, maxSegments: Int, deadlineMillis: Long) {
        val state = recorder.state
        if (state !is TimeshiftRecorder.State.Recording) {
            line("FAIL", "qa-timeshift-bounded", "recording ended before the window filled: ${describe(state)}")
            awaitStop(recorder, System.currentTimeMillis() + STOP_DEADLINE_MILLIS)
            return
        }
        if (recorder.completedSegments < maxSegments + EXTRA_SEGMENTS) {
            if (System.currentTimeMillis() > deadlineMillis) {
                line("FAIL", "qa-timeshift-bounded", "the window did not fill within ${RECORD_DEADLINE_MILLIS / 1000} seconds")
                stopAndCheck(recorder)
                return
            }
            handler.postDelayed({ awaitFullWindow(recorder, maxSegments, deadlineMillis) }, POLL_MILLIS)
            return
        }
        checkBounded(recorder, maxSegments)
    }

    private fun checkBounded(recorder: TimeshiftRecorder, maxSegments: Int) {
        val files = ringDirectory.listFiles().orEmpty().count { it.name.startsWith("seg-") }
        val playlist = if (recorder.playlist.isFile) recorder.playlist.readText() else ""
        val listed = playlist.lines().count { it.startsWith("#EXTINF") }
        // One file is the segment being written, so the ring holds at most the window plus one.
        val bounded = files <= maxSegments + 1 && listed <= maxSegments
        // The first file ever written must be gone. The media sequence cannot show this: a live channel starts at a
        // high sequence number, so it is above zero even before anything is evicted.
        val oldestEvicted = !File(ringDirectory, "seg-0.ts").exists()
        if (!bounded || !oldestEvicted) {
            line("FAIL", "qa-timeshift-bounded", "files=$files listed=$listed window=$maxSegments oldest-evicted=$oldestEvicted")
            stopAndCheck(recorder)
            return
        }
        line(
            "PASS",
            "qa-timeshift-bounded",
            "files=$files listed=$listed window=$maxSegments segments=${recorder.completedSegments} oldest-evicted=true",
        )
        // The player's own check writes its PASS or FAIL line. Stopping happens whatever it found.
        playLocal(android.net.Uri.fromFile(recorder.playlist).toString()) { _ -> stopAndCheck(recorder) }
    }

    private fun stopAndCheck(recorder: TimeshiftRecorder) {
        recorder.stop()
        awaitStop(recorder, System.currentTimeMillis() + STOP_DEADLINE_MILLIS)
    }

    private fun awaitStop(recorder: TimeshiftRecorder, deadlineMillis: Long) {
        val state = recorder.state
        if (state is TimeshiftRecorder.State.Idle || state is TimeshiftRecorder.State.Recording) {
            if (System.currentTimeMillis() > deadlineMillis) {
                line("FAIL", "qa-timeshift-cleanup", "the recorder did not stop within ${STOP_DEADLINE_MILLIS / 1000} seconds")
                return
            }
            handler.postDelayed({ awaitStop(recorder, deadlineMillis) }, POLL_MILLIS)
            return
        }
        val left = ringDirectory.listFiles().orEmpty().count { it.name.startsWith("seg-") }
        val cancelled = state is TimeshiftRecorder.State.Stopped &&
            state.reason == com.devtunasoft.tuneros.core.HlsCaptureEngine.StopReason.CANCELLED
        if (left == 0 && !ringDirectory.exists() && cancelled) {
            line("PASS", "qa-timeshift-cleanup", "stopped by request, ring removed")
        } else {
            line("FAIL", "qa-timeshift-cleanup", "segment files left=$left, ${describe(state)}")
        }
    }

    private fun describe(state: TimeshiftRecorder.State): String = when (state) {
        is TimeshiftRecorder.State.Idle -> "idle"
        is TimeshiftRecorder.State.Recording -> "recording"
        is TimeshiftRecorder.State.Stopped -> "stopped: ${state.reason}"
        is TimeshiftRecorder.State.Failed -> "failed: ${state.error}"
    }

    private fun line(outcome: String, id: String, detail: String) {
        report.appendText("$outcome\t$id\t$detail\n")
    }

    private companion object {
        const val RECORD_DEADLINE_MILLIS = 90_000L
        const val STOP_DEADLINE_MILLIS = 20_000L
        const val POLL_MILLIS = 500L
        const val EXTRA_SEGMENTS = 3
    }
}

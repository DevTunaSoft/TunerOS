package com.devtunasoft.tuneros

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.ChannelVisibility
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideParser
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.PlaylistParser
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.SourceDownloader
import com.devtunasoft.tuneros.core.SourceRecoveryPolicy
import com.devtunasoft.tuneros.core.StreamRecorder
import com.devtunasoft.tuneros.core.TimelineFocusPolicy
import com.devtunasoft.tuneros.core.TimelineLayout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream

/**
 * In-app, unattended, read-only (except owned scratch data) Android acceptance probes.
 * Runs on MainActivity's existing serial worker; no threads, timers or network requests.
 * All test fixtures are synthetic. Real provider, USB removal and power-cut scenarios
 * must never be reported as passed by these smoke tests.
 */
internal object OnDeviceSelfTest {
    enum class State { PASS, FAIL, BLOCKED, NOT_RUN }
    enum class Event {
        PLAYLIST_IMPORTED, PLAYLIST_IMPORT_FAILED, PLAYLIST_IMPORT_MILLIS,
        GUIDE_IMPORTED, GUIDE_IMPORT_FAILED, GUIDE_IMPORT_MILLIS,
        PLAYBACK_REQUESTED, PLAYBACK_READY, PLAYBACK_STARTED, PLAYBACK_ERROR,
        PLAYBACK_FIRST_FRAME, PLAYBACK_TIMEOUT,
        RECORDING_STARTED, RECORDING_COMPLETED, RECORDING_PARTIAL,
        RECORDING_FAILED, RECORDING_CANCELLED, RECORDING_RECOVERED,
        RECORDING_PLAYBACK_REQUESTED, RECORDING_PLAYBACK_READY,
        RECORDING_PLAYBACK_STARTED, RECORDING_PLAYBACK_FIRST_FRAME,
        RECORDING_PLAYBACK_ERROR, RECORDING_PLAYBACK_TIMEOUT,
        DVR_REARM_EXACT_COUNT, DVR_REARM_BLOCKED_COUNT, DVR_REARM_FAILURE_COUNT,
        DVR_ALARM_DELIVERED, DVR_SCHEDULED_SERVICE_REQUESTED,
        UI_FOCUS_PROBE_PASS, UI_FOCUS_PROBE_FAIL,
        UI_EMPTY_TIMELINE_PASS, UI_EMPTY_TIMELINE_FAIL
    }

    data class Check(val id: String, val state: State, val detail: String)
    data class Report(
        val wallMillis: Long,
        val monotonicMillis: Long,
        val sdk: Int,
        val pssKiB: Long,
        val checks: List<Check>,
    ) {
        fun summary(): String {
            val passed = checks.count { it.state == State.PASS }
            val failed = checks.count { it.state == State.FAIL }
            val blocked = checks.count { it.state == State.BLOCKED }
            val notRun = checks.count { it.state == State.NOT_RUN }
            val disposition = when {
                failed > 0 -> "FAIL"
                blocked > 0 || notRun > 0 -> "INCOMPLETE"
                else -> "PASS"
            }
            return "Autonomous QA: $disposition | $passed PASS / $failed FAIL / $blocked BLOCKED / $notRun NOT RUN"
        }

        fun text(): String = buildString(2048) {
            appendLine("TunerOS device self-test (no private URLs or provider headers)")
            appendLine("Wall clock (epoch ms): $wallMillis")
            appendLine("Elapsed since boot (ms): $monotonicMillis")
            appendLine("Android API: $sdk")
            appendLine("Process PSS snapshot (KiB): $pssKiB (not a memory benchmark)")
            appendLine(summary())
            for (check in checks) {
                appendLine("${check.state}\t${check.id}\t${check.detail}")
            }
        }
    }

    private const val REPORT_FILE = "tuneros-selftest-report.txt"
    private const val EVENTS_FILE = "tuneros-selftest-events.tsv"
    private const val MAX_EVENTS_BYTES = 32 * 1024
    private const val MAX_SHARE_EVENTS = 8 * 1024
    private const val CANARY_KEY = "tuneros_selftest_canary_v1"
    private const val RUN_MARKER = "tuneros-selftest-in-progress"
    private val eventLock = Any() // Never held while executing lengthy self-tests.

    private fun check(id: String, detail: String, test: () -> Boolean): Check =
        try {
            Check(id, if (test()) State.PASS else State.FAIL, detail)
        } catch (error: Exception) {
            // Never persist exception messages, source URLs, file paths or credentials.
            Check(id, State.FAIL, "Exception: " + error.javaClass.simpleName)
        }

    /** Safe to call at startup or after an import. No existing recordings are touched. */
    @Synchronized
    fun run(
        context: Context,
        hasPlaylist: Boolean,
        hasGuide: Boolean,
        includeStress: Boolean = false,
    ): Report {
        val previousRunIncomplete = File(context.filesDir, RUN_MARKER).exists()
        val marker = File(context.filesDir, RUN_MARKER)
        marker.writeText(System.currentTimeMillis().toString())
        val checks = ArrayList<Check>(25)
        checks += Check(
            "previous-test-run",
            if (previousRunIncomplete) State.BLOCKED else State.PASS,
            if (previousRunIncomplete)
                "Previous test run did not finish; review app termination or storage"
            else "No interrupted self-test detected",
        )
        checks += check(
            "source-recovery-policy",
            "No prior source overwritten by failed, empty, stale or edited imports",
        ) {
            val saved = "https://example.invalid/qa-playlist"
            SourceRecoveryPolicy.shouldRestore(saved, saved, false, false) &&
                !SourceRecoveryPolicy.shouldRestore(null, saved, false, false) &&
                !SourceRecoveryPolicy.shouldRestore("", "", false, false) &&
                !SourceRecoveryPolicy.shouldRestore(saved, "", false, false) &&
                !SourceRecoveryPolicy.shouldRestore(saved, saved, true, false) &&
                !SourceRecoveryPolicy.shouldRestore(saved, saved, false, true) &&
                SourceRecoveryPolicy.mayCommit(true, 3, 3) &&
                !SourceRecoveryPolicy.mayCommit(false, 3, 3) &&
                !SourceRecoveryPolicy.mayCommit(true, 3, 4)
        }

        checks += check("offline-m3u-parser", "One synthetic channel parsed") {
            val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"selftest\",Test Channel\nhttps://example.invalid/live.m3u8\n"
            val parsed = PlaylistParser.parse(m3u.reader())
            parsed.size == 1 &&
                parsed.single().guideId == "selftest" &&
                parsed.single().id == "3c9e156eef326c82f9371e59"
        }

        checks += check("offline-source-stall", "Non-progressing source fails without a CPU spin") {
            var emptyReads = 0
            val stalled = object : InputStream() {
                private var header = 0
                override fun read(): Int = when (header++) {
                    0 -> 'a'.code
                    1 -> 'b'.code
                    else -> -1
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    emptyReads++
                    return 0
                }
            }
            val downloader = SourceDownloader { url ->
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = HTTP_OK
                    override fun getInputStream(): InputStream = stalled
                }
            }
            val error = runCatching {
                downloader.open("https://example.invalid/qa", 128).use { it.skip(256) }
            }.exceptionOrNull()
            error is IOException && emptyReads in 16..17
        }

        checks += check("offline-xmltv-parser", "Synthetic XMLTV programme indexed") {
            val xml = """
                <tv><channel id="selftest"><display-name>Test Channel</display-name></channel>
                <programme channel="selftest" start="20261111120000 +0000"
                stop="20261111130000 +0000"><title>Test Programme</title></programme></tv>
            """.trimIndent()
            val guide: GuideSnapshot = GuideParser.parse(ByteArrayInputStream(xml.toByteArray()))
            guide.prepareIndex()
            val program = guide.programs.single()
            guide.hasSchedule("selftest") &&
                guide.now("selftest", program.startEpochSeconds + 1)?.title == "Test Programme"
        }

        checks += check("offline-channel-filter", "Search and hide-no-EPG respect channel mapping") {
            val testChannels = listOf(
                Channel("1", "News", "https://example.invalid/1", guideId = "news"),
                Channel("2", "Sports", "https://example.invalid/2", guideId = "sports"),
            )
            val testGuide = GuideSnapshot(
                listOf(GuideProgram("news", "News at noon", 100, 200)),
                emptyMap(),
            )
            testGuide.prepareIndex()
            ChannelVisibility.filter(testChannels, testGuide, "news", true).map { it.id } ==
                listOf("1") &&
                ChannelVisibility.filter(testChannels, testGuide, "", true).size == 1 &&
                ChannelVisibility.filter(testChannels, null, "", false).size == 2
        }

        checks += check("offline-timeline", "Indexed guide produces contiguous clipped cable cells") {
            val programs = listOf(
                GuideProgram("news", "First", 90, 120),
                GuideProgram("news", "Second", 140, 190),
            )
            val snapshot = GuideSnapshot(programs, emptyMap()).apply { prepareIndex() }
            val row = TimelineLayout.rowFromSorted(snapshot.programsBetween("news", 100, 180), 100, 180)
            row.first().startEpochSeconds == 100L &&
                row.last().endEpochSeconds == 180L &&
                row.zipWithNext().all { (left, right) ->
                    left.endEpochSeconds == right.startEpochSeconds
                } &&
                row.mapNotNull { it.program?.title } == listOf("First", "Second") &&
                TimelineFocusPolicy.shiftedIndex(row, 140L, -1) == 0 &&
                TimelineFocusPolicy.shiftedIndex(row, 90L, 1) == 2 &&
                GuideSnapshot(
                    listOf(
                        GuideProgram("overlap", "Long", 0, 500),
                        GuideProgram("overlap", "Expired", 10, 20),
                        GuideProgram("overlap", "Current", 100, 160),
                    ),
                    emptyMap(),
                ).apply { prepareIndex() }
                    .programsBetween("overlap", 120, 200)
                    .map { it.title } == listOf("Long", "Current")
        }

        checks += check("offline-xmltv-dtd-block", "DTD and entity injection rejected") {
            val attack = """<!DOCTYPE tv [<!ENTITY probe SYSTEM "file:///private">]><tv>&probe;</tv>"""
            runCatching { GuideParser.parse(ByteArrayInputStream(attack.toByteArray())) }.isFailure
        }

        checks += check("scratch-dvr-copy", "1 KiB direct-to-disk test, temp file removed") {
            val bytes = ByteArray(1024) { (it % 251).toByte() }
            val scratch = File.createTempFile("tuneros-qa-", ".tmp", context.cacheDir)
            try {
                val result = FileOutputStream(scratch).use { output ->
                    StreamRecorder(bufferBytes = 4096, minFreeBytes = 0).copy(
                        ByteArrayInputStream(bytes), output, freeBytes = { Long.MAX_VALUE },
                    )
                }
                result.stopReason == StreamRecorder.StopReason.END_OF_STREAM &&
                    result.bytesWritten == bytes.size.toLong() &&
                    scratch.readBytes().contentEquals(bytes)
            } finally {
                kotlin.check(scratch.delete()) { "Could not clean up self-test scratch file" }
            }
        }

        checks += check(
            "scratch-dvr-catalog",
            "Isolated SQLite: insert, collision, protection, progress and interrupted recovery",
        ) {
            // Use a dedicated, disposable database. Never open, modify or delete
            // recordings.db or any actual recording media during QA.
            val name = "tuneros-selftest-recordings.db"
            context.deleteDatabase(name) // Remove only a stale self-test database.
            try {
                RecordingCatalog(context, name).use { catalog ->
                    val inserted = catalog.tryInsertStart(
                        "qa-only-id", "Synthetic QA recording", "qa-channel", 100L,
                    )
                    val duplicateRejected = !catalog.tryInsertStart(
                        "qa-only-id", "Other title", "qa-channel", 101L,
                    )
                    catalog.updateProgress("qa-only-id", 8192L)
                    val protected = catalog.setProtected("qa-only-id", true) == 1
                    val recovered = catalog.markInterruptedRecordings(200L) == 1
                    val found = catalog.recent(1).singleOrNull()
                    inserted && duplicateRejected && protected && recovered &&
                        found != null && found.id == "qa-only-id" &&
                        found.title == "Synthetic QA recording" &&
                        found.state == "PARTIAL" && found.bytesWritten == 8192L &&
                        found.isProtected && found.stoppedEpochSeconds == 200L
                }
            } finally {
                kotlin.check(context.deleteDatabase(name)) {
                    "Could not remove the isolated QA SQLite database"
                }
            }
        }

        checks += check("android-keystore-roundtrip", "Dedicated encrypted canary saved/read/removed") {
            val store = SecureSourceStore(context)
            try {
                val saved = store.save(CANARY_KEY, "tuneros-local-check")
                saved && store.load(CANARY_KEY) == "tuneros-local-check"
            } finally {
                store.delete(CANARY_KEY)
            }
        }

        val canSchedule = runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }.getOrDefault(false)
        checks += if (canSchedule) {
            Check("exact-dvr-alarm-access", State.PASS, "Exact alarm capability available; wake-from-sleep not yet tested")
        } else {
            Check("exact-dvr-alarm-access", State.BLOCKED, "Android has not granted exact alarms; no permission prompts issued")
        }

        val tree = RecordingDestinationStore(context).selectedTreeUri()
        checks += if (tree == null) {
            Check("external-recording-storage", State.NOT_RUN, "No optional removable/shared recording destination selected")
        } else {
            val info = SafStorageInspector.inspect(context, tree)
            when {
                info == null -> Check("external-recording-storage", State.FAIL, "Selected document tree is not currently accessible/writable")
                info.availableBytes < RecordingPolicy.DEFAULT_MIN_FREE_BYTES ->
                    Check("external-recording-storage", State.BLOCKED, "Storage reports insufficient free space")
                else -> Check("external-recording-storage", State.PASS, "Selected tree accessible; removable-drive stress NOT RUN")
            }
        }

        checks += Check("playlist-source-loaded",
            if (hasPlaylist) State.PASS else State.NOT_RUN,
            if (hasPlaylist) "Imported channel list exists" else "No user-supplied playlist imported")
        checks += Check("guide-source-loaded",
            if (hasGuide) State.PASS else State.NOT_RUN,
            if (hasGuide) "Imported programme guide exists" else "No user-supplied guide imported")
        val observedPlayback = hasEvent(context, Event.PLAYBACK_STARTED)
        checks += Check("live-provider-playback",
            if (observedPlayback) State.PASS else State.NOT_RUN,
            if (observedPlayback)
                "A player previously reached isPlaying; decoder/format coverage unverified"
            else "No real provider stream has reached isPlaying in retained observations")
        checks += Check("live-first-video-frame",
            if (hasEvent(context, Event.PLAYBACK_FIRST_FRAME)) State.PASS else State.NOT_RUN,
            if (hasEvent(context, Event.PLAYBACK_FIRST_FRAME))
                "At least one video frame was rendered on this installation"
            else "No on-device video frame callback has been observed yet")
        val completedRecording = hasEvent(context, Event.RECORDING_COMPLETED)
        checks += Check("real-dvr-completion",
            if (completedRecording) State.PASS else State.NOT_RUN,
            if (completedRecording) "A real recording completed with nonzero media bytes"
            else "No completed real recording has been observed")
        val recoveredRecording = hasEvent(context, Event.RECORDING_RECOVERED)
        checks += Check("dvr-interruption-recovery",
            if (recoveredRecording) State.PASS else State.NOT_RUN,
            if (recoveredRecording) "At least one interrupted catalog row recovered as partial"
            else "No interruption recovery has been observed")
        val playedRecording = hasEvent(context, Event.RECORDING_PLAYBACK_STARTED)
        checks += Check("recording-playback-started",
            if (playedRecording) State.PASS else State.NOT_RUN,
            if (playedRecording) "Saved media previously started playback on this installation"
            else "No saved recording playback started in retained observations")
        val recordedFirstFrame = hasEvent(context, Event.RECORDING_PLAYBACK_FIRST_FRAME)
        checks += Check("recording-video-frame",
            if (recordedFirstFrame) State.PASS else State.NOT_RUN,
            if (recordedFirstFrame) "At least one saved recording video frame was rendered"
            else "No recording video frame observed")
        checks += mostRecentRearmResult(context)
        if (includeStress) {
            checks += OnDeviceGuideStress.run()
            checks += mostRecentUiFocusResult(context)
            checks += mostRecentEmptyTimelineResult(context)
        }
        val deliveredAlarm = hasEvent(context, Event.DVR_ALARM_DELIVERED)
        checks += Check("scheduled-dvr-alarm-delivery",
            if (deliveredAlarm) State.PASS else State.NOT_RUN,
            if (deliveredAlarm) "Android delivered at least one scheduled recording alarm"
            else "No scheduled alarm callback observed; permission alone is not proof")
        checks += Check("reboot-usb-and-long-run",
            State.NOT_RUN, "Physical power cuts, USB removal and extended RAM stress cannot be simulated safely")

        val report = Report(
            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            Build.VERSION.SDK_INT, Debug.getPss(), checks,
        )
        // Publish a fully written report atomically; a crash must not expose a
        // partial PASS report to CI or the user.
        val pending = File(context.filesDir, "$REPORT_FILE.pending")
        pending.writeText(report.text())
        kotlin.check(pending.renameTo(File(context.filesDir, REPORT_FILE))) {
            "Could not publish completed self-test report"
        }
        kotlin.check(marker.delete()) { "Could not clear self-test run marker" }
        return report
    }

    /**
     * Called only on the existing worker. Numeric event data prevents private stream
     * URLs, error messages, titles or tokens from entering persistent diagnostics.
     * Event file is strictly bounded, with no unbounded in-memory history.
     */
    fun observe(context: Context, event: Event, metric: Long = 0) {
        // Lock only the small, bounded event file. Recording finalization is never
        // forced to wait for guide parsing, SQLite checks or stress testing.
        synchronized(eventLock) {
            val file = File(context.filesDir, EVENTS_FILE)
            val line = "${System.currentTimeMillis()}\t${SystemClock.elapsedRealtime()}" +
                "\t${event.name}\t$metric\t${Debug.getPss()}\n"
            if (file.length() + line.length > MAX_EVENTS_BYTES) {
                file.writeText("")
            }
            file.appendText(line)
        }
    }

    private fun hasEvent(context: Context, event: Event): Boolean = synchronized(eventLock) {
        val file = File(context.filesDir, EVENTS_FILE)
        file.exists() && file.length() <= MAX_EVENTS_BYTES &&
            file.useLines { lines -> lines.any { line -> line.contains("\t${event.name}\t") } }
    }

    /**
     * Latest debug-only focus probe wins, including a later recovery from a failure.
     * This does not claim D-pad navigation or Android TV device certification.
     */
    private fun mostRecentUiFocusResult(context: Context): Check = synchronized(eventLock) {
        val file = File(context.filesDir, EVENTS_FILE)
        if (!file.exists() || file.length() > MAX_EVENTS_BYTES) {
            return@synchronized Check("debug-ui-focus", State.NOT_RUN, "No UI focus probe observed")
        }
        var result: Check? = null
        file.forEachLine { line ->
            when (line.split('\t', limit = 4).getOrNull(2)) {
                Event.UI_FOCUS_PROBE_PASS.name ->
                    result = Check("debug-ui-focus", State.PASS,
                        "Actual TunerOS controls accepted keyboard/remote focus")
                Event.UI_FOCUS_PROBE_FAIL.name ->
                    result = Check("debug-ui-focus", State.FAIL,
                        "A visible TunerOS control did not accept focus")
            }
        }
        return@synchronized result ?: Check("debug-ui-focus", State.NOT_RUN, "No UI focus probe observed")
    }
    /** The actual synthetic ProgramTimelineView dispatched left/right key handlers. */
    private fun mostRecentEmptyTimelineResult(context: Context): Check = synchronized(eventLock) {
        val file = File(context.filesDir, EVENTS_FILE)
        if (!file.exists() || file.length() > MAX_EVENTS_BYTES) {
            return@synchronized Check(
                "debug-empty-guide-dpad", State.NOT_RUN, "No empty-guide UI probe observed",
            )
        }
        var state = State.NOT_RUN
        file.forEachLine { line ->
            when (line.split('\t', limit = 4).getOrNull(2)) {
                Event.UI_EMPTY_TIMELINE_PASS.name -> state = State.PASS
                Event.UI_EMPTY_TIMELINE_FAIL.name -> state = State.FAIL
            }
        }
        Check(
            "debug-empty-guide-dpad", state,
            when (state) {
                State.PASS -> "Actual View accepted left/right keys without launching a stream"
                State.FAIL -> "Empty-guide row lost D-pad navigation or triggered playback"
                else -> "No empty-guide UI probe observed"
            },
        )
    }

    /**
     * The last nonempty re-arm batch wins. The recorder writes EXACT, then
     * BLOCKED, then FAILED counters; if a batch has mixed outcomes, the
     * highest-severity outcome is its final observation. No schedules are
     * created by the reporter.
     */
    private fun mostRecentRearmResult(context: Context): Check = synchronized(eventLock) {
        val file = File(context.filesDir, EVENTS_FILE)
        if (!file.exists() || file.length() > MAX_EVENTS_BYTES) {
            return@synchronized Check("dvr-rearm", State.NOT_RUN, "No pending DVR schedules re-armed")
        }
        var result: Check? = null
        file.forEachLine { line ->
            val columns = line.split('\t', limit = 5)
            val count = columns.getOrNull(3)?.toLongOrNull() ?: return@forEachLine
            val event = columns.getOrNull(2)
            result = when (event) {
                Event.DVR_REARM_EXACT_COUNT.name ->
                    Check("dvr-rearm", State.PASS,
                        "$count pending exact alarm(s) submitted; firing not yet verified")
                Event.DVR_REARM_BLOCKED_COUNT.name ->
                    Check("dvr-rearm", State.BLOCKED,
                        "$count pending alarm(s) could not be armed without OS permission")
                Event.DVR_REARM_FAILURE_COUNT.name ->
                    Check("dvr-rearm", State.FAIL,
                        "$count alarm re-arm request(s) raised errors")
                else -> result
            }
        }
        return@synchronized result ?: Check("dvr-rearm", State.NOT_RUN, "No pending DVR schedules re-armed")
    }
    fun latestText(context: Context): String {
        val report = File(context.filesDir, REPORT_FILE)
        val reportText = if (report.exists() && report.length() <= 8 * 1024) {
            report.readText()
        } else {
            "On-device self-test has not completed yet."
        }
        val observations = File(context.filesDir, EVENTS_FILE)
        val eventsText = synchronized(eventLock) {
            if (observations.exists() && observations.length() <= MAX_EVENTS_BYTES) {
                observations.readText().takeLast(MAX_SHARE_EVENTS)
            } else {
                "None."
            }
        }
        return reportText + "\nDevice observations (wall ms, uptime ms, event, numeric value, PSS KiB):\n" +
            eventsText
    }
}

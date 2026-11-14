package com.devtunasoft.tuneros

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import com.devtunasoft.tuneros.core.AbortableSlot
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.ChannelVisibility
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideParser
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.HlsCaptureEngine
import com.devtunasoft.tuneros.core.HlsCaptureSource
import com.devtunasoft.tuneros.core.PlaylistParser
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.SourceDownloader
import com.devtunasoft.tuneros.core.SourceRecoveryPolicy
import com.devtunasoft.tuneros.core.StreamRecorder
import com.devtunasoft.tuneros.core.TimelineFocusPolicy
import com.devtunasoft.tuneros.core.TimelineLayout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.net.URL
import java.io.File
import java.io.FileOutputStream

/**
 * In-app, unattended, read-only (except owned scratch data) Android acceptance probes.
 * Runs on MainActivity's existing serial worker. Only the explicit debug QA path
 * starts a short-lived localhost fixture thread; production makes no QA network calls.
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
        DVR_PREPARATIONS_RECOVERED,
        DVR_ALARM_DELIVERED, DVR_SCHEDULED_SERVICE_REQUESTED,
        DVR_SCHEDULED_SERVICE_ACCEPTED,
        UI_FOCUS_PROBE_PASS, UI_FOCUS_PROBE_FAIL,
        UI_EMPTY_TIMELINE_PASS, UI_EMPTY_TIMELINE_FAIL,
        UI_CLOCK_MARKER_PASS, UI_CLOCK_MARKER_FAIL
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
                    override fun getResponseCode() = HttpURLConnection.HTTP_OK
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

        checks += check(
            "scratch-dvr-schedule-reservation",
            "Isolated SQLite: immutable ID, PREPARING gate, overlap and bounded paging",
        ) {
            val name = "tuneros-selftest-schedules.db"
            context.deleteDatabase(name) // Owned, disposable DB only; not user schedules.
            try {
                RecordingScheduleStore(context, name).use { store ->
                    val first = RecordingScheduleEntry(
                        "qa-scheduled-one", "Original title", "qa-channel",
                        "qa-only-source-key", 100L, 200L, "PREPARING", false, null,
                    )
                    val second = RecordingScheduleEntry(
                        "qa-scheduled-two", "Other title", "qa-channel-two",
                        "qa-other-source-key", 220L, 300L, "PREPARING", false, null,
                    )
                    val created = store.insertIfAbsent(first)
                    val duplicateRejected = !store.insertIfAbsent(first.copy(
                        title = "Overwritten title",
                        secureSourceKey = "wrong-source-key",
                        state = "SCHEDULED",
                    ))
                    val original = store.get(first.id)
                    val preserved = original?.title == first.title &&
                        original.secureSourceKey == first.secureSourceKey &&
                        original.state == "PREPARING"
                    val preventsPrematureAlarm = store.pendingAfter(50L).isEmpty()
                    val preparingConflicts = store.findOverlap(
                        "qa-different-id", 110L, 190L,
                    )?.id == first.id
                    val activated = store.markScheduledIfPreparing(first.id) &&
                        !store.markScheduledIfPreparing(first.id)
                    val createdSecond = store.insertIfAbsent(second) &&
                        store.markScheduledIfPreparing(second.id)
                    val firstPage = store.pendingAfter(50L, limit = 1)
                    val secondPage = firstPage.singleOrNull()?.let {
                        store.pendingAfter(50L, limit = 1, after = it)
                    }
                    val pagination = firstPage.singleOrNull()?.id == first.id &&
                        secondPage?.singleOrNull()?.id == second.id
                    val wrongSourceBlocked = !store.markStartedIfScheduled(
                        first.id, "wrong-source-key",
                    )
                    val acknowledged = store.markStartedIfScheduled(
                        first.id, first.secureSourceKey,
                    ) && !store.markStartedIfScheduled(first.id, first.secureSourceKey)
                    val startedImmutable = !store.insertIfAbsent(first) &&
                        store.get(first.id)?.state == "STARTED"
                    val startedOverlap = store.findOverlap(
                        "qa-different-id", 110L, 190L,
                    )?.id == first.id
                    val nextPending = store.pendingAfter(50L, limit = 1)
                        .singleOrNull()?.id == second.id
                    val wrongFailureBlocked = !store.markFailedIfScheduled(
                        second.id, "wrong-source-key", "Recorder busy",
                    )
                    val failedExactlyOnce = store.markFailedIfScheduled(
                        second.id, second.secureSourceKey, "Recorder busy",
                    ) && !store.markFailedIfScheduled(
                        second.id, second.secureSourceKey, "Recorder busy",
                    ) && store.get(second.id)?.state == "FAILED" &&
                        store.pendingAfter(50L).isEmpty()
                    created && duplicateRejected && preserved &&
                        preventsPrematureAlarm && preparingConflicts &&
                        activated && createdSecond && pagination &&
                        wrongSourceBlocked && acknowledged &&
                        startedImmutable && startedOverlap && nextPending &&
                        wrongFailureBlocked && failedExactlyOnce
                }
            } finally {
                kotlin.check(context.deleteDatabase(name)) {
                    "Could not remove isolated QA schedule database"
                }
            }
        }

        // The 40-row SQLite fault-injection probe must never run during
        // ordinary app startup. The Android emulator opts into stress QA.
        if (includeStress) checks += check(
            "scratch-dvr-preparation-recovery",
            "Isolated SQLite startup cleanup: bounded pages, retry and no active schedule loss",
        ) {
            val name = "tuneros-selftest-preparation-recovery.db"
            context.deleteDatabase(name)
            try {
                RecordingScheduleStore(context, name).use { store ->
                    val template = RecordingScheduleEntry(
                        "qa-orphan-000", "Synthetic orphan", "qa-channel",
                        "qa-orphan-secret", 100L, 200L,
                        "PREPARING", false, null,
                    )
                    var seeded = true
                    for (i in 0 until 40) {
                        val id = "qa-orphan-" + i.toString().padStart(3, '0')
                        seeded = seeded && store.insertIfAbsent(
                            template.copy(id = id, secureSourceKey = "secret-$id"),
                        )
                    }
                    val active = template.copy(
                        id = "qa-real-scheduled", secureSourceKey = "qa-active-secret",
                        state = "SCHEDULED",
                    )
                    seeded = seeded && store.insertIfAbsent(active)
                    val colliding = store.findOverlap("qa-new", 110L, 190L) != null

                    val blockedKey = "secret-qa-orphan-000"
                    val firstPass = RecordingScheduler.reclaimAbandonedPreparations(
                        store,
                        deleteSecrets = { it != blockedKey },
                        maxEntries = 40,
                    )
                    val failedReserved = store.get("qa-orphan-000")?.state == "RECOVERING" &&
                        !store.insertIfAbsent(template) &&
                        !store.markScheduledIfPreparing("qa-orphan-000")
                    val successfulGone = store.get("qa-orphan-039") == null
                    val activePreserved = store.get(active.id)?.state == "SCHEDULED" &&
                        store.get(active.id)?.secureSourceKey == active.secureSourceKey
                    val secondPass = RecordingScheduler.reclaimAbandonedPreparations(
                        store, deleteSecrets = { true }, maxEntries = 40,
                    )
                    val canRebook = store.insertIfAbsent(template) &&
                        store.markScheduledIfPreparing(template.id)
                    seeded && colliding && firstPass == 39 && failedReserved &&
                        successfulGone && activePreserved && secondPass == 1 &&
                        canRebook && store.get(active.id)?.state == "SCHEDULED"
                }
            } finally {
                check(context.deleteDatabase(name)) {
                    "Could not remove isolated preparation recovery database"
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
            checks += check(
                "debug-xmltv-depth-limit",
                "Android XML reader rejects a well-formed 65-level nested document",
            ) {
                val inner = "<nest>".repeat(GuideParser.MAX_XML_DEPTH)
                val closer = "</nest>".repeat(GuideParser.MAX_XML_DEPTH)
                val deep = "<tv>" + inner + closer + "</tv>"
                runCatching {
                    GuideParser.parse(ByteArrayInputStream(deep.toByteArray(Charsets.UTF_8)))
                }.exceptionOrNull() is org.xml.sax.SAXException
            }
            checks += check(
                "debug-hls-dvr-sequence",
                "Offline HLS parse + Android segment copy preserve live-edge deduplication",
            ) { debugHlsDvrSequence() }
            checks += check(
                "debug-hls-response-length",
                "Declared HTTP segment lengths must match copied bytes",
            ) { debugHlsResponseLength() }
            checks += check(
                "debug-loopback-hls-transport",
                "Android HTTP loopback fetch + bounded file-backed HLS capture and cleanup",
            ) { debugLoopbackHlsTransport(context) }
            checks += check(
                "debug-connection-abort",
                "Stop aborts a blocked Android HTTP connect/read promptly",
            ) { debugConnectionAbort() }
            checks += check(
                "debug-service-stop-cancels",
                "Foreground recorder: Stop during a blocked connect ends CANCELLED in a scratch catalog",
            ) { debugServiceStopCancels(context) }
            checks += check(
                "debug-internal-dvr-finalize",
                "Owned file writes, collision protection, finalization and cleanup",
            ) { debugInternalSinkFinalization(context) }
            checks += check(
                "debug-saf-storage-guard",
                "Synthetic removable-storage free-space and root matching fail closed",
            ) {
                RecordingPolicy.isDocumentOnRoot("primary:Movies", "primary:") &&
                    !RecordingPolicy.isDocumentOnRoot("primary2:Movies", "primary") &&
                    RecordingPolicy.isDocumentOnRoot("ABCD-1234:Shows", "ABCD-1234") &&
                    !RecordingPolicy.isDocumentOnRoot("ABCD-12345:Shows", "ABCD-1234") &&
                    RecordingPolicy.remainingFreeBytes(null, 16L * 1024 * 1024) == 0L &&
                    RecordingPolicy.remainingFreeBytes(500L, 100L) == 400L &&
                    RecordingPolicy.remainingFreeBytes(500L, 700L) == 0L
            }
            checks += OnDeviceGuideStress.run()
            // Scan the bounded event log once for all debug UI probes.
            checks.addAll(recentUiChecks(context))
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

    /**
     * Two-byte debug-only recording pipeline smoke test. Never opens a provider,
     * creates a recording file, schedules an alarm, or allocates media buffers.
     */
    /**
     * Debug-only end-to-end HLS parser + Android segment copier + capture engine.
     * One fixed 64 KiB recorder buffer; synthetic one-byte TS payloads only.
     * The connection objects never access network or user recording storage.
     */
    private fun debugHlsDvrSequence(): Boolean {
        val manifestUrl = "https://example.invalid/live.m3u8"
        val firstManifest = """#EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-TARGETDURATION:2
            #EXTINF:2,
            7.ts
            #EXTINF:2,
            8.ts
        """.trimIndent()
        val secondManifest = """#EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-TARGETDURATION:2
            #EXTINF:2,
            7.ts
            #EXTINF:2,
            8.ts
            #EXTINF:2,
            9.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        var manifestLoads = 0
        var copiedSegments = 0
        val source = AndroidHlsCaptureSource(
            openConnection = { url, range ->
                check(range == null)
                val payload = when (url) {
                    manifestUrl -> {
                        manifestLoads++
                        check(manifestLoads <= 2)
                        if (manifestLoads == 1) firstManifest.toByteArray(Charsets.UTF_8)
                            else secondManifest.toByteArray(Charsets.UTF_8)
                    }
                    "https://example.invalid/8.ts" -> {
                        check(copiedSegments == 0)
                        copiedSegments++
                        byteArrayOf(1)
                    }
                    "https://example.invalid/9.ts" -> {
                        check(copiedSegments == 1)
                        copiedSegments++
                        byteArrayOf(2)
                    }
                    else -> error("Unexpected offline HLS test resource")
                }
                object : HttpURLConnection(URL(url)) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getInputStream(): InputStream = ByteArrayInputStream(payload)
                }
            },
            freeBytes = { Long.MAX_VALUE },
        )
        val output = ByteArrayOutputStream(2)
        val result = HlsCaptureEngine().record(
            initialUrl = manifestUrl,
            output = output,
            endEpochSeconds = 1_000L,
            source = source,
            cancelled = { false },
            nowEpochSeconds = { 100L },
            sleepMillis = {},
        )
        return result.stopReason == HlsCaptureEngine.StopReason.END_LIST &&
            result.lastSequence == 9L &&
            result.bytesWritten == 2L &&
            copiedSegments == 2 &&
            manifestLoads == 2 &&
            output.toByteArray().contentEquals(byteArrayOf(1, 2))
    }

    /**
     * Debug-only Android HTTP integration test using a bounded loopback fixture.
     * It serves four tiny replies on 127.0.0.1 and writes two bytes into
     * a private scratch file, verifying the bytes and deleting it afterward.
     * No user/provider requests, recordings, permission prompts or services.
     * Only the debug-enabled worker runs this; the serving thread always exits.
     */
    /**
     * Checks the production HTTP segment copier with two fake media bytes.
     * Fail closed for undersized/oversized identity bodies, retain valid and
     * unknown-length sources. No sockets, media files or production workers.
     */
    private fun debugHlsResponseLength(): Boolean {
        val payload = byteArrayOf(17, 23)
        val source = AndroidHlsCaptureSource(
            openConnection = { url, range ->
                check(range == null)
                object : HttpURLConnection(URL(url)) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getInputStream(): InputStream = ByteArrayInputStream(payload)
                    override fun getHeaderField(name: String?): String? {
                        if (!"Content-Length".equals(name, ignoreCase = true)) return null
                        return when (url.substringAfterLast('/')) {
                            "too-short.ts" -> "4"
                            "too-long.ts" -> "1"
                            "complete.ts" -> "2"
                            "unknown.ts" -> null
                            else -> error("Unexpected length probe URL")
                        }
                    }
                }
            },
            freeBytes = { Long.MAX_VALUE },
        )
        fun copied(segment: String, shouldFail: Boolean): Boolean {
            val bytes = ByteArrayOutputStream(2)
            val outcome = runCatching {
                source.copySegment(
                    "https://example.invalid/$segment",
                    null, bytes, { false }, {},
                )
            }
            return bytes.toByteArray().contentEquals(payload) &&
                if (shouldFail) outcome.exceptionOrNull() is IOException
                else outcome.getOrNull()?.let {
                    it.stopReason == StreamRecorder.StopReason.END_OF_STREAM &&
                        it.bytesWritten == 2L
                } == true
        }
        return copied("too-short.ts", true) &&
            copied("too-long.ts", true) &&
            copied("complete.ts", false) &&
            copied("unknown.ts", false)
    }

    private fun debugLoopbackHlsTransport(context: Context): Boolean {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(loopback, 0), 1)
            soTimeout = 4_000
        }
        server.use {
            val sourceUrl = "http://127.0.0.1:${server.localPort}/live.m3u8"
            val first = """#EXTM3U
                #EXT-X-MEDIA-SEQUENCE:7
                #EXT-X-TARGETDURATION:2
                #EXTINF:2,
                7.ts
                #EXTINF:2,
                8.ts
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val second = """#EXTM3U
                #EXT-X-MEDIA-SEQUENCE:7
                #EXT-X-TARGETDURATION:2
                #EXTINF:2,
                7.ts
                #EXTINF:2,
                8.ts
                #EXTINF:2,
                9.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val serverFailure = AtomicReference<Throwable?>(null)
            var served = 0
            var manifests = 0
            val fixture = Thread({
                try {
                    repeat(4) {
                        server.accept().use { socket ->
                            socket.soTimeout = 4_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val request = input.readLine() ?: error("Missing HTTP request")
                            // Do not leave unread HTTP headers on the socket.
                            while (true) {
                                val line = input.readLine() ?: error("Truncated HTTP headers")
                                if (line.isEmpty()) break
                            }
                            val path = request.substringAfter(' ').substringBefore(' ')
                            val bytes = when (path) {
                                "/live.m3u8" -> {
                                    manifests++
                                    check(manifests <= 2)
                                    if (manifests == 1) first else second
                                }
                                "/8.ts" -> byteArrayOf(1)
                                "/9.ts" -> byteArrayOf(2)
                                else -> error("Unexpected loopback path")
                            }
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            val output = socket.getOutputStream()
                            output.write(response.toByteArray(Charsets.US_ASCII))
                            output.write(bytes)
                            output.flush()
                            served++
                        }
                    }
                } catch (failure: Exception) {
                    serverFailure.set(failure)
                }
            }, "tuneros-debug-http-fixture").apply {
                isDaemon = true
                start()
            }
            var copiedCorrectly = false
            try {
                val source = AndroidHlsCaptureSource(
                    openConnection = { url, range ->
                        check(range == null)
                        (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                            connectTimeout = 4_000
                            readTimeout = 4_000
                            instanceFollowRedirects = false
                        }
                    },
                    freeBytes = { Long.MAX_VALUE },
                )
                // Use actual filesystem I/O, not a ByteArrayOutputStream.
                // Random, app-private scratch name prevents touching user media.
                val scratch = File.createTempFile("tuneros-hls-qa-", ".partial", context.cacheDir)
                try {
                    val result = FileOutputStream(scratch).use { output ->
                        HlsCaptureEngine().record(
                            initialUrl = sourceUrl,
                            output = output,
                            endEpochSeconds = 1_000L,
                            source = source,
                            cancelled = { false },
                            nowEpochSeconds = { 100L },
                            sleepMillis = {},
                        )
                    }
                    // Read only the expected two bytes; never buffer a recording
                    // into memory for verification.
                    val fileMatches = scratch.length() == 2L &&
                        scratch.inputStream().use { input ->
                            input.read() == 1 && input.read() == 2 && input.read() == -1
                        }
                    copiedCorrectly = result.stopReason == HlsCaptureEngine.StopReason.END_LIST &&
                        result.lastSequence == 9L && result.bytesWritten == 2L &&
                        fileMatches
                } finally {
                    check(scratch.delete()) { "Could not clean up HLS QA scratch file" }
                }
            } finally {
                // Closing the server unblocks a stalled accept; no thread persists.
                server.close()
                fixture.join(5_000L)
            }
            return copiedCorrectly && !fixture.isAlive &&
                serverFailure.get() == null && served == 4 && manifests == 2
        }
    }

    /**
     * Debug-only acceptance of the production internal recording sink. Uses
     * a unique app-private cache name and never touches the recordings folder.
     * One tiny write, two collision checks, a rename and final cleanup.
     */
    /**
     * A local server accepts but never answers. The app's connection is registered, blocks in
     * responseCode, and is aborted through the same slot the recorder uses on Stop/destroy.
     * The blocked call must throw within a bounded time. No external network is used.
     */
    private fun debugConnectionAbort(): Boolean {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = AtomicReference<java.net.Socket?>(null)
            val acceptor = Thread { runCatching { accepted.set(server.accept()) } }
            acceptor.isDaemon = true
            acceptor.start()
            val connection = URL("http://127.0.0.1:" + server.localPort + "/")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val slot = AbortableSlot<HttpURLConnection> { it.disconnect() }
            val thrown = AtomicReference<Throwable?>(null)
            val reader = Thread {
                if (slot.register(connection)) {
                    try {
                        connection.responseCode
                    } catch (error: Exception) {
                        thrown.set(error)
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            try {
                acceptor.join(5_000)
                if (accepted.get() == null) return false
                slot.abortCurrent()
                reader.join(5_000)
                return !reader.isAlive && thrown.get() != null
            } finally {
                accepted.get()?.close()
                connection.disconnect()
            }
        }
    }

    /**
     * Starts the real DvrRecordingService against a silent loopback fixture, waits until the
     * request is in flight, then sends Stop. The scratch catalog row must end CANCELLED.
     * Uses a disposable database; recordings.db and real media are never touched.
     */
    private fun debugServiceStopCancels(context: Context): Boolean {
        val database = "tuneros-selftest-service.db"
        val id = "qa-service-" + System.currentTimeMillis()
        context.deleteDatabase(database)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = AtomicReference<java.net.Socket?>(null)
            val acceptor = Thread { runCatching { accepted.set(server.accept()) } }
            acceptor.isDaemon = true
            acceptor.start()
            DvrRecordingService.qaCatalogDatabaseName = database
            try {
                val end = System.currentTimeMillis() / 1000L + 600L
                val start = Intent(context, DvrRecordingService::class.java)
                    .setAction(DvrRecordingService.ACTION_START)
                    .putExtra(DvrRecordingService.EXTRA_ID, id)
                    .putExtra(DvrRecordingService.EXTRA_TITLE, "Synthetic QA stop")
                    .putExtra(DvrRecordingService.EXTRA_CHANNEL_ID, "qa-channel")
                    .putExtra(
                        DvrRecordingService.EXTRA_URL,
                        "http://127.0.0.1:" + server.localPort + "/silent.ts",
                    )
                    .putExtra(DvrRecordingService.EXTRA_END_EPOCH_SECONDS, end)
                context.startForegroundService(start)
                if (!waitUntil(15_000) { accepted.get() != null }) return false
                context.startService(
                    Intent(context, DvrRecordingService::class.java)
                        .setAction(DvrRecordingService.ACTION_STOP),
                )
                val entry = waitUntilValue(20_000) {
                    RecordingCatalog(context, database).use { catalog ->
                        catalog.recent(10).firstOrNull { it.id == id }
                    }?.takeIf { it.state != "RECORDING" }
                }
                return entry != null && entry.state == "CANCELLED" &&
                    entry.bytesWritten == 0L && entry.stoppedEpochSeconds != null
            } finally {
                DvrRecordingService.qaCatalogDatabaseName = null
                accepted.get()?.close()
                context.deleteDatabase(database)
            }
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean =
        waitUntilValue(timeoutMillis) { if (condition()) true else null } == true

    private fun <T : Any> waitUntilValue(timeoutMillis: Long, read: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    private fun debugInternalSinkFinalization(context: Context): Boolean {
        val id = "qa-dvr-${SystemClock.elapsedRealtimeNanos()}-${Thread.currentThread().id}"
        val partial = File(context.cacheDir, "$id.partial")
        val complete = File(context.cacheDir, "$id.ts")
        val sink = InternalRecordingSink(context.cacheDir, id, ".ts")
        try {
            sink.openOutput().use { it.write(byteArrayOf(4, 5, 6)) }
            val partialCollisionBlocked = runCatching {
                InternalRecordingSink(context.cacheDir, id, ".ts").openOutput().use { }
            }.isFailure
            val counted = sink.bytesWritten == 3L && partial.length() == 3L
            val location = sink.finalizeRecording()
            val completedCollisionBlocked = runCatching {
                InternalRecordingSink(context.cacheDir, id, ".ts").openOutput().use { }
            }.isFailure
            val completeBytes = complete.length() == 3L &&
                complete.inputStream().use { input ->
                    input.read() == 4 && input.read() == 5 &&
                        input.read() == 6 && input.read() == -1
                }
            return partialCollisionBlocked && counted && completedCollisionBlocked &&
                location == complete.absolutePath && !partial.exists() && completeBytes
        } finally {
            // Verify the sink deletes only the file it owns, whether the
            // scenario succeeded or threw partway through finalization.
            check(sink.delete()) { "Could not remove owned DVR QA file" }
            check(!partial.exists() && !complete.exists()) { "DVR QA file remains" }
        }
    }

    private fun hasEvent(context: Context, event: Event): Boolean = synchronized(eventLock) {
        val file = File(context.filesDir, EVENTS_FILE)
        file.exists() && file.length() <= MAX_EVENTS_BYTES &&
            file.useLines { lines -> lines.any { line -> line.contains("\t${event.name}\t") } }
    }

    /**
     * Read one bounded event log for the latest result of each debug-only
     * Android UI probe. Avoid repeating file scans and per-line split lists.
     */
    private fun recentUiChecks(context: Context): List<Check> = synchronized(eventLock) {
        var focus = State.NOT_RUN
        var focusFailureMask = 0L
        var emptyGuide = State.NOT_RUN
        var marker = State.NOT_RUN
        val file = File(context.filesDir, EVENTS_FILE)
        if (file.exists() && file.length() <= MAX_EVENTS_BYTES) {
            file.forEachLine { line ->
                when {
                    line.contains("\tUI_FOCUS_PROBE_PASS\t") -> {
                        focus = State.PASS
                        focusFailureMask = 0L
                    }
                    line.contains("\tUI_FOCUS_PROBE_FAIL\t") -> {
                        focus = State.FAIL
                        focusFailureMask = line.substringAfter("\tUI_FOCUS_PROBE_FAIL\t")
                            .substringBefore('\t').toLongOrNull() ?: -1L
                    }
                    line.contains("\tUI_EMPTY_TIMELINE_PASS\t") -> emptyGuide = State.PASS
                    line.contains("\tUI_EMPTY_TIMELINE_FAIL\t") -> emptyGuide = State.FAIL
                    line.contains("\tUI_CLOCK_MARKER_PASS\t") -> marker = State.PASS
                    line.contains("\tUI_CLOCK_MARKER_FAIL\t") -> marker = State.FAIL
                }
            }
        }
        listOf(
            Check(
                "debug-ui-focus", focus,
                when (focus) {
                    State.PASS -> "Actual controls accepted focus traversal"
                    State.FAIL -> "D-pad focus probe failed (mask=$focusFailureMask; " +
                        "1=source, 2=search, 4=filter, 8=search-return, " +
                        "16=next-target, 32=next-focus)"
                    else -> "No control focus probe observed"
                },
            ),
            Check(
                "debug-empty-guide-dpad", emptyGuide,
                when (emptyGuide) {
                    State.PASS -> "Empty EPG View accepted D-pad keys without playback"
                    State.FAIL -> "Empty guide lost navigation or attempted playback"
                    else -> "No empty-guide D-pad probe observed"
                },
            ),
            Check(
                "debug-now-marker", marker,
                when (marker) {
                    State.PASS -> "Canvas now-marker moved/cleared without rebinding EPG"
                    State.FAIL -> "Synthetic clock-marker redraw failed"
                    else -> "No clock-marker drawing probe observed"
                },
            ),
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

package com.devtunasoft.tuneros

import android.app.Activity
import android.app.AlertDialog
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import android.view.View
import android.view.KeyEvent
import android.content.pm.ApplicationInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.ParentalAttemptLimit
import com.devtunasoft.tuneros.core.ParentalPin
import com.devtunasoft.tuneros.core.ParentalPolicy
import com.devtunasoft.tuneros.core.ChannelVisibility
import com.devtunasoft.tuneros.core.FavoriteChannels
import com.devtunasoft.tuneros.core.GuideDoctor
import com.devtunasoft.tuneros.core.GuideParser
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.SeriesRules
import com.devtunasoft.tuneros.core.SourceDownloader
import com.devtunasoft.tuneros.core.SourceRecoveryPolicy
import com.devtunasoft.tuneros.core.StreamDoctor
import com.devtunasoft.tuneros.core.PlayerReusePolicy
import com.devtunasoft.tuneros.core.PlaylistParser
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.TimelineEntry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** First playable TunerOS slice: HTTP M3U/XMLTV import, searchable TV guide, Media3 playback. */
class MainActivity : Activity() {
    private companion object {
        const val STATE_SEARCH = "tuneros.state.search"
        const val STATE_WINDOW = "tuneros.state.window"
    }

    private data class RetainedGuideState(
        val channels: List<Channel>,
        val guide: GuideSnapshot?,
        val guideWindowStart: Long,
        val searchQuery: String,
    )

    private val prefs by lazy { getSharedPreferences("tuneros_alpha", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val playlistEpoch = AtomicInteger()
    private val guideEpoch = AtomicInteger()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val secureSources by lazy { SecureSourceStore(this) }
    private val recordingDestinations by lazy { RecordingDestinationStore(this) }
    private val recordingTreeRequestCode = 1701
    private val downloader = SourceDownloader()
    private val searchRefresh = Runnable { renderChannels() }
    private val clockRefresh = object : Runnable {
        override fun run() {
            // No background wakeups while the activity is stopped or has no EPG.
            if (!activityStarted || showingRecordings || guide == null ||
                isFinishing || isDestroyed
            ) return
            // A clock change never requires a dataset refresh or programme rebind.
            if (::channelAdapter.isInitialized && ::channelList.isInitialized) {
                channelAdapter.refreshClock(channelList, System.currentTimeMillis() / 1000)
            }
            uiHandler.postDelayed(this, 60_000)
        }
    }

    /** Keep at most one minute callback, and none outside the visible EPG. */
    private fun syncGuideClock() {
        uiHandler.removeCallbacks(clockRefresh)
        if (activityStarted && !showingRecordings && guide != null) {
            uiHandler.post(clockRefresh)
        }
    }

    private var channels: List<Channel> = emptyList()
    private var guide: GuideSnapshot? = null
    // Cache filter results between clock ticks and guide-window shifts.
    // ListView virtualizes views, so all imported channels can remain accessible.
    private var filteredSource: List<Channel>? = null
    private var filteredGuide: GuideSnapshot? = null
    private var filteredQuery: String? = null
    private var filteredHideNoGuide = false
    private var activityStarted = false
    private var filteredChannels: List<Channel> = emptyList()
    private var filteredFavorites: Set<String>? = null
    private var filteredFavoritesOnly = false
    private var favorites: Set<String> = emptySet()
    private lateinit var favoriteStore: FavoriteChannelStore
    private lateinit var favoritesOnly: CheckBox
    // Guide Doctor: the user's channel-to-guide mappings, applied to the channel list before filtering.
    private var guideMappings: Map<String, String> = emptyMap()
    private lateinit var guideMappingStore: GuideMappingStore
    private lateinit var guideDoctorButton: Button
    private var mappedSource: List<Channel>? = null
    private var mappedFor: Map<String, String>? = null
    private var mappedChannels: List<Channel> = emptyList()
    private val guideDoctorListLimit = 300
    private var player: ExoPlayer? = null
    // Reuse a single decoder for channels with identical provider request headers.
    private var playerHeaders: Map<String, String>? = null
    /** The timeshift running for the channel in the player, and that channel's live URL. Null while timeshift is off. */
    private var timeshift: TimeshiftRecorder? = null
    private var timeshiftLiveUri: String? = null
    /** The parental PIN and restricted groups. Unlocking lasts until the app process ends. */
    private val parentalStore by lazy { ParentalControlsStore(this) }
    private var parentalUnlocked = false
    private var playbackEvidence: FirstResponseRecorder? = null
    private var activeChannelName: String? = null
    private var focusedChannel: Channel? = null
    private var focusedProgram: GuideProgram? = null
    private var guideWindowStart: Long = 0
    private val guideWindowSeconds = 3 * 3600L
    private val guideStepSeconds = 30 * 60L
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private lateinit var playerView: PlayerView
    private lateinit var channelList: ListView
    private lateinit var channelAdapter: CableGuideAdapter
    private lateinit var recordingAdapter: RecordingLibraryAdapter
    private var showingRecordings = false
    private var focusedRecording: RecordingCatalogEntry? = null
    private var playingRecordingId: String? = null
    private lateinit var sourcePanel: LinearLayout
    private lateinit var sourcesButton: Button
    private lateinit var playlistUrl: EditText
    private lateinit var guideUrl: EditText
    private lateinit var search: EditText
    private lateinit var hideNoGuide: CheckBox
    private lateinit var status: TextView
    private lateinit var diagnosticsSummary: TextView
    private var selfTestsQueued = false
    private var selfTestsDirty = false
    private var playbackTimeout: Runnable? = null
    private lateinit var timeRail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isRotationLaunch()) recordRotationCreate(savedInstanceState != null)
        if (isPickFolderLaunch()) android.os.Handler(mainLooper).post { chooseRecordingStorage() }
        if (isInternalStorageLaunch()) recordingDestinations.clear()
        qaScheduledUrl()?.let { url -> worker.execute { OnDeviceSelfTest.runScheduledRecordingOnly(applicationContext, url) } }
        if (isInvalidStartLaunch()) worker.execute { OnDeviceSelfTest.runInvalidStartOnly(applicationContext) }
        if (isSeriesCheckLaunch()) worker.execute { OnDeviceSelfTest.runSeriesRulesOnly(applicationContext) }
        qaRecordUrl()?.let { url ->
            val deadline = intent?.getStringExtra("tuneros.debug.record_mode") == "deadline"
            // The deadline recording is then played back from its own file (first video frame).
            val onRecorded: ((java.io.File) -> Unit)? = if (deadline) {
                { file ->
                    runOnUiThread {
                        runQaPlayback(
                            android.net.Uri.fromFile(file).toString(),
                            java.io.File(filesDir, "tuneros-recording-report.txt"),
                            "qa-recording-playback-first-frame",
                            reset = false,
                        )
                    }
                }
            } else {
                null
            }
            worker.execute { OnDeviceSelfTest.runRecordingOnly(applicationContext, url, deadline, onRecorded) }
        }
        val retained = lastNonConfigurationInstance as? RetainedGuideState
        if (retained != null) {
            channels = retained.channels
            guide = retained.guide
            guideWindowStart = retained.guideWindowStart
        } else {
            // A Bundle holds only small UI metadata. Never parcel 250,000 EPG
            // entries or provider URLs: Android may kill a process after saving.
            guideWindowStart = savedInstanceState?.getLong(STATE_WINDOW) ?: 0L
        }
        window.decorView.setBackgroundColor(Color.rgb(13, 21, 34))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = dp(12)
            val vertical = dp(10)
            setPadding(horizontal, vertical, horizontal, vertical)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(horizontal + insets.systemWindowInsetLeft,
                    vertical + insets.systemWindowInsetTop,
                    horizontal + insets.systemWindowInsetRight,
                    vertical + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        sourcesButton = button("Hide sources") {
            setSourcesVisible(sourcePanel.visibility != android.view.View.VISIBLE)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("TunerOS · Classic Cable Alpha", 22),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sourcesButton)
        })
        sourcePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        playlistUrl = urlField("M3U playlist URL", "")
        sourcePanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playlistUrl, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Load channels") { loadPlaylist() })
        })
        guideUrl = urlField("XMLTV guide URL (optional)", "")
        sourcePanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(guideUrl, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Load guide") { loadGuide() })
        })
        root.addView(sourcePanel)
        setSourcesVisible(retained?.guide == null)
        search = urlField(
            "Search channels",
            retained?.searchQuery ?: savedInstanceState?.getString(STATE_SEARCH).orEmpty(),
        ).apply {
            // Search is local UI state, not a feed URL. Bound the retained
            // query to avoid oversized lifecycle Bundles or pointless scans.
            filters = arrayOf(InputFilter.LengthFilter(256))
        }
        root.addView(search)
        hideNoGuide = CheckBox(this).apply {
            text = "Hide channels with no EPG"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("hide_without_guide", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("hide_without_guide", checked).apply()
                renderChannels()
            }
        }
        root.addView(hideNoGuide)
        favoriteStore = FavoriteChannelStore(this)
        favorites = favoriteStore.load()
        favoritesOnly = CheckBox(this).apply {
            text = "Favorites only"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("favorites_only", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("favorites_only", checked).apply()
                if (checked && favorites.isEmpty()) {
                    showStatus("No favorites yet. Long-press a channel to add it.")
                }
                renderChannels()
            }
        }
        root.addView(favoritesOnly)
        guideMappingStore = GuideMappingStore(this)
        guideMappings = guideMappingStore.load()
        guideDoctorButton = button("Guide Doctor") { openGuideDoctor() }
        root.addView(guideDoctorButton)
        playerView = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
            isFocusable = true
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 0.7f))
        status = label("Enter an M3U URL to begin", 13)
        root.addView(status)
        diagnosticsSummary = label("Autonomous QA: scheduled", 12)
        root.addView(diagnosticsSummary)
        // A single focus-scrollable action row keeps the TV guide visible on short screens.
        val dvrActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Record focused program") { recordFocusedProgram() })
            addView(button("Series focused program") { toggleSeriesRule() })
            addView(button("Cancel scheduled recording") { cancelFocusedSchedule() })
            addView(button("Exact DVR timing") { openExactAlarmSettings() })
            addView(button("Stop recording") { stopRecording() })
            addView(button("Timeshift on / off") { toggleTimeshift() })
            addView(button("Back 10 s") { timeshiftBack() })
            addView(button("Live") { timeshiftLive() })
            addView(button("Parental PIN") { openParentalPin() })
            addView(button("Restrict group") { restrictGroupPrompt(restricted = true) })
            addView(button("Release group") { restrictGroupPrompt(restricted = false) })
            addView(button("Guide / Recordings") { toggleRecordings() })
            addView(button("Protect / Unprotect") { toggleFocusedRecordingProtection() })
            addView(button("Delete recording") { deleteFocusedRecording() })
            addView(button("Choose recording storage") { chooseRecordingStorage() })
            addView(button("Use internal recording storage") { useInternalRecordingStorage() })
            addView(button("Self-test report") { showDiagnostics() })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(dvrActions)
        })
        timeRail = label("", 14).apply {
            setBackgroundColor(Color.rgb(18, 31, 50))
            setPadding(dp(194), dp(8), dp(8), dp(8))
        }
        root.addView(timeRail)
        channelAdapter = CableGuideAdapter(
            this,
            onChannelSelected = ::play,
            onProgramFocused = { channel, program ->
                focusedChannel = channel
                focusedProgram = program
                showStatus(channel.name + (program?.let { " · " + it.title } ?: ""))
            },
            onWindowShift = { direction ->
                shiftGuideWindow(direction * guideStepSeconds)
            },
            onChannelLongPressed = ::toggleFavorite,
        )
        recordingAdapter = RecordingLibraryAdapter(
            this,
            ::playRecording,
        ) { entry ->
            focusedRecording = entry
            showStatus(entry.title + if (entry.isProtected) " · Protected" else " · Recording")
        }
        channelList = ListView(this).apply {
            adapter = channelAdapter
            isFocusable = true
            dividerHeight = 0
        }
        root.addView(channelList, LinearLayout.LayoutParams(-1, 0, 1.3f))
        if (retained != null) {
            renderChannels()
            showStatus(
                "Restored ${retained.channels.size} channels" +
                    if (retained.guide == null) "" else " and guide",
            )
        }
        // Reclaim only reservations abandoned by an earlier app process.
        // This is queued first on the existing serial worker, before any
        // user-initiated booking can begin. Never run Keystore/SQLite on UI.
        worker.execute {
            RecordingScheduler.recoverAbandonedPreparations(applicationContext)
        }
        // Run bounded offline QA before potentially slow DVR alarm re-arming.
        scheduleSelfTests()
        worker.execute {
            val playlist = runCatching { secureSources.load("playlist") }.getOrNull()
            val epg = runCatching { secureSources.load("guide") }.getOrNull()
            val hadPendingSchedules = runCatching {
                RecordingScheduler.rearmPending(this)
            }.getOrDefault(false)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (playlistUrl.text.isEmpty()) playlist?.let { playlistUrl.setText(it) }
                    if (guideUrl.text.isEmpty()) epg?.let { guideUrl.setText(it) }
                    // Avoid a second expensive parser/Keystore/SQLite QA pass on
                    // the ordinary startup path with no scheduled DVR jobs.
                    if (hadPendingSchedules) scheduleSelfTests()
                    if (retained == null) {
                        // Rebuild transient guide/channel state after process death
                        // using saved encrypted source references. No large Bundle
                        // and no separate worker. A user-initiated import wins.
                        if (playlist != null && SourceRecoveryPolicy.shouldRestore(
                                savedUrl = playlist,
                                displayedUrl = playlistUrl.text.toString(),
                                hasLoadedData = channels.isNotEmpty(),
                                hasActiveImport = playlistEpoch.get() != 0,
                            )
                        ) {
                            importPlaylist(playlist, saveOnSuccess = false)
                        }
                        if (epg != null && SourceRecoveryPolicy.shouldRestore(
                                savedUrl = epg,
                                displayedUrl = guideUrl.text.toString(),
                                hasLoadedData = guide != null,
                                hasActiveImport = guideEpoch.get() != 0,
                            )
                        ) {
                            importGuide(epg, saveOnSuccess = false)
                        }
                    }
                }
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                uiHandler.removeCallbacks(searchRefresh)
                uiHandler.postDelayed(searchRefresh, 160)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        // Only debug APKs explicitly launched for automated CI perform focus
        // probes. Normal users never have their focus moved by testing.
        if (isDebugQaEnabled()) root.post { runDebugUiFocusProbe() }
        if (isServiceStopLaunch()) {
            worker.execute { OnDeviceSelfTest.runServiceStopOnly(applicationContext) }
        }
        if (isParentalCheckLaunch()) worker.execute { ParentalQa.run(applicationContext) }
        if (isAlarmLaunch()) {
            worker.execute { OnDeviceSelfTest.runAlarmOnly(applicationContext) }
        }
        qaPlaybackUrl()?.let { url -> root.post { runQaPlayback(url) } }
        qaTimeshiftUrl()?.let { url -> root.post { runQaTimeshift(url) } }
        qaTimeshiftControlsUrl()?.let { url -> root.post { runQaTimeshiftControls(url) } }
        qaImportUrls()?.let { (playlist, guide) -> root.post { runQaImport(playlist, guide) } }
        // Debug-only startup-recovery QA. Runs synchronously, so the field and import state are set before the
        // startup restore callback that the worker posts to the UI thread.
        qaRecoveryMode()?.let { mode -> runRecoveryQa(mode) }
        if (isKeyLaunch()) root.post { armQaKeyReport() }
    }

    /** Debug-only: opens the recording folder picker so CI can select a folder on the emulator. */
    private fun isPickFolderLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.pick_folder", false) == true

    /** Debug-only: clears the saved recording folder so the checks after the external-storage check use internal storage. */
    private fun isInternalStorageLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.use_internal_storage", false) == true

    /** Debug-only: an http(s) URL used by an isolated scheduled-recording launch. */
    private fun qaScheduledUrl(): String? =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra("tuneros.debug.scheduled_url")
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } else {
            null
        }

    /** Debug-only: an http(s) URL the recorder service copies in an isolated CI launch. */
    private fun qaRecordUrl(): String? =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra("tuneros.debug.record_url")
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } else {
            null
        }

    /** Debug-only: an isolated CI launch that checks the activity is recreated after rotation. */
    private fun isRotationLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.rotation", false) == true

    private fun recordRotationCreate(recreated: Boolean) {
        val report = java.io.File(filesDir, "tuneros-rotation-report.txt")
        if (recreated) {
            report.appendText("PASS\tqa-rotation-recreate\trecreated after orientation change\n")
        } else {
            report.writeText("CREATED\n")
        }
    }

    /** Debug-only: an isolated CI launch that records remote key input reaching this activity. */
    private fun isKeyLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.keys", false) == true

    private var qaKeyReportFile: java.io.File? = null
    private var qaKeyCount = 0
    private var qaKeyFirstFocus: Any? = null
    private var qaKeyFocusChanged = false

    private fun armQaKeyReport() {
        val report = java.io.File(filesDir, "tuneros-key-report.txt")
        report.writeText("ARMED\n")
        qaKeyFirstFocus = currentFocus
        qaKeyReportFile = report
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        val report = qaKeyReportFile
        if (report != null) {
            report.appendText("KEY\t" + event.keyCode + "\t" + event.action + "\n")
        }
        if (report != null && event.action == KeyEvent.ACTION_UP) {
            qaKeyCount++
            if (currentFocus !== qaKeyFirstFocus) qaKeyFocusChanged = true
            if (qaKeyCount == 4) {
                report.appendText("PASS\tqa-remote-key-input\tkeys=4 focusChanged=" + qaKeyFocusChanged + "\n")
            }
        }
        return handled
    }

    /** Debug-only: playlist and guide URLs imported by an isolated CI launch. Never saved. */
    private fun qaImportUrls(): Pair<String, String>? {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null
        val playlist = intent?.getStringExtra("tuneros.debug.import_playlist_url") ?: return null
        val guide = intent?.getStringExtra("tuneros.debug.import_guide_url") ?: return null
        val validPlaylist = playlist.startsWith("http://") || playlist.startsWith("https://")
        val validGuide = guide.startsWith("http://") || guide.startsWith("https://")
        return if (validPlaylist && validGuide) playlist to guide else null
    }

    /** Debug-only startup-recovery QA: "seed" saves a user import, "bad" tries a failing import, "check" only cold-starts. */
    private fun qaRecoveryMode(): String? =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra("tuneros.debug.recovery_mode")?.takeIf { it == "seed" || it == "bad" || it == "check" }
        } else {
            null
        }

    /** Debug-only: an http(s) URL for a startup-recovery QA launch. Never logged or written to a report. */
    private fun qaRecoveryUrl(extra: String): String? =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra(extra)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } else {
            null
        }

    // Debug-only startup-recovery QA. Its report holds PASS/FAIL, ids and counts, never a URL.
    private var recoveryBefore: String? = null

    private fun appendRecoveryLine(state: String, id: String, detail: String) {
        java.io.File(filesDir, "tuneros-recovery-report.txt").appendText("$state\t$id\t$detail\n")
    }

    /** Polls on the UI thread until [ready] holds, then calls [done] with true, or with false at the deadline. */
    private fun awaitRecovery(ready: () -> Boolean, done: (Boolean) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 90_000L
        lateinit var poll: Runnable
        poll = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            when {
                ready() -> done(true)
                SystemClock.elapsedRealtime() >= deadline -> done(false)
                else -> uiHandler.postDelayed(poll, 250L)
            }
        }
        uiHandler.post(poll)
    }

    private fun loadedCounts(): String = "channels=${channels.size} programs=${guide?.programs?.size ?: 0}"

    private fun runRecoveryQa(mode: String) {
        when (mode) {
            "seed" -> {
                val playlist = qaRecoveryUrl("tuneros.debug.recovery_playlist_url")
                val guideSource = qaRecoveryUrl("tuneros.debug.recovery_guide_url")
                if (playlist == null || guideSource == null) {
                    appendRecoveryLine("FAIL", "qa-startup-recovery-seed", "missing URL extras")
                    return
                }
                importPlaylist(playlist, saveOnSuccess = true)
                importGuide(guideSource, saveOnSuccess = true)
                awaitRecovery(ready = { channels.isNotEmpty() && guide != null }) { loaded ->
                    val counts = loadedCounts()
                    worker.execute {
                        val saved = secureSources.load("playlist") == playlist &&
                            secureSources.load("guide") == guideSource
                        appendRecoveryLine(
                            if (loaded && saved) "PASS" else "FAIL",
                            "qa-startup-recovery-seed",
                            "$counts saved=$saved",
                        )
                    }
                }
            }
            "check" -> awaitRecovery(ready = { channels.isNotEmpty() && guide != null }) { loaded ->
                appendRecoveryLine(if (loaded) "PASS" else "FAIL", "qa-startup-recovery", loadedCounts())
            }
            "bad" -> {
                val badPlaylist = qaRecoveryUrl("tuneros.debug.recovery_playlist_url")
                if (badPlaylist == null) {
                    appendRecoveryLine("FAIL", "qa-startup-recovery-bad-import", "missing URL extra")
                    return
                }
                // Set the field and start the import before the startup restore callback can run, so the restore
                // cannot start for this field. Read the saved playlist on the worker before the import starts.
                playlistUrl.setText(badPlaylist)
                worker.execute {
                    recoveryBefore = runCatching { secureSources.load("playlist") }.getOrNull()
                }
                importPlaylist(badPlaylist, saveOnSuccess = true)
                awaitRecovery(ready = { qaPlaylistFailure != null }) { failed ->
                    worker.execute {
                        val after = runCatching { secureSources.load("playlist") }.getOrNull()
                        val unchanged = failed && recoveryBefore != null && recoveryBefore == after
                        val detail = if (unchanged) "failed import left the saved playlist unchanged" else "failed=$failed unchanged=false"
                        appendRecoveryLine(if (unchanged) "PASS" else "FAIL", "qa-startup-recovery-bad-import", detail)
                    }
                }
            }
        }
    }

    // Debug-only QA: the class name of the last failed import. Never a URL or an error message.
    private var qaPlaylistFailure: String? = null
    private var qaGuideFailure: String? = null

    /**
     * Runs the real playlist and guide imports (without saving the URLs) and reports the parsed
     * counts once both are loaded, or FAIL at the deadline. Counts are checked by the CI script.
     */
    private fun runQaImport(playlistUrl: String, guideUrl: String) {
        val report = java.io.File(filesDir, "tuneros-import-report.txt")
        report.writeText("RUNNING\n")
        importPlaylist(playlistUrl, saveOnSuccess = false)
        importGuide(guideUrl, saveOnSuccess = false)
        val handler = android.os.Handler(mainLooper)
        // Public guides can be large and slow on the emulator; allow several minutes.
        val deadline = android.os.SystemClock.elapsedRealtime() + 300_000
        lateinit var poll: Runnable
        poll = Runnable {
            val channelCount = channels.size
            val programCount = guide?.programs?.size ?: 0
            val failure = qaPlaylistFailure ?: qaGuideFailure
            when {
                channelCount > 0 && programCount > 0 -> report.appendText(
                    "PASS\tqa-loopback-import\tchannels=" + channelCount + " programs=" + programCount + "\n",
                )
                // Fail as soon as an import reports an error (class name only), not at the deadline.
                failure != null -> report.appendText(
                    "FAIL\tqa-loopback-import\tchannels=" + channelCount + " programs=" + programCount +
                        " reason=" + failure + "\n",
                )
                android.os.SystemClock.elapsedRealtime() > deadline -> report.appendText(
                    "FAIL\tqa-loopback-import\tchannels=" + channelCount + " programs=" + programCount +
                        " reason=timeout\n",
                )
                else -> handler.postDelayed(poll, 500)
            }
        }
        handler.postDelayed(poll, 500)
    }

    /** Debug-only: an http(s) URL to decode in an isolated CI launch. Never set for users. */
    private fun qaPlaybackUrl(): String? =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra("tuneros.debug.playback_url")
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } else {
            null
        }

    /** Debug-only: an http(s) channel URL for the timeshift controls check in an isolated CI launch. Never set for users. */
    private fun qaTimeshiftControlsUrl(): String? =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra("tuneros.debug.timeshift_controls_url")
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } else {
            null
        }

    /**
     * Debug-only timeshift controls check on scratch data. It plays a live channel, turns timeshift on, and then checks in
     * turn that the player switches to the ring, advances, steps back, returns to the live edge, turns timeshift off, and
     * that the ring is removed. Each step writes one PASS or FAIL line to the timeshift controls report.
     */
    private fun runQaTimeshiftControls(url: String) {
        val report = java.io.File(filesDir, "tuneros-timeshift-controls-report.txt")
        report.writeText("RUNNING\n")
        play(Channel(id = "qa-timeshift-controls", name = "qa-timeshift-controls", streamUrl = url))
        qaAwait(report, "qa-timeshift-first-frame", 60_000L, {
            player?.isPlaying == true && (player?.currentPosition ?: 0L) > 1_000L
        }) {
            toggleTimeshift()
            qaAwait(report, "qa-timeshift-on", 60_000L, { isShowingRing(player) }) {
                qaAwait(report, "qa-timeshift-advances", 60_000L, { (player?.currentPosition ?: 0L) > 12_000L }) {
                    val before = player?.currentPosition ?: 0L
                    timeshiftBack()
                    qaAwait(report, "qa-timeshift-back", 15_000L, {
                        (player?.currentPosition ?: Long.MAX_VALUE) < before - 5_000L
                    }) {
                        timeshiftLive()
                        qaAwait(report, "qa-timeshift-live", 15_000L, {
                            (player?.currentLiveOffset ?: Long.MAX_VALUE) in 0L..8_000L
                        }) {
                            toggleTimeshift()
                            qaAwait(report, "qa-timeshift-off", 15_000L, { isLiveHttp(player) }) {
                                qaAwait(report, "qa-timeshift-cleanup", 20_000L, {
                                    !java.io.File(cacheDir, "timeshift").exists()
                                }) {}
                            }
                        }
                    }
                }
            }
        }
    }

    /** Polls [condition] twice a second. Writes PASS and runs [then] once it holds, or writes FAIL after [timeoutMillis]. */
    private fun qaAwait(
        report: java.io.File,
        checkId: String,
        timeoutMillis: Long,
        condition: () -> Boolean,
        then: () -> Unit,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        fun poll() {
            when {
                condition() -> {
                    report.appendText("PASS\t$checkId\tmet\n")
                    then()
                }
                System.currentTimeMillis() > deadline ->
                    report.appendText("FAIL\t$checkId\tnot met within ${timeoutMillis / 1000} seconds\n")
                else -> uiHandler.postDelayed({ poll() }, 500L)
            }
        }
        poll()
    }

    /** Debug-only: an http(s) URL for the timeshift check in an isolated CI launch. Never set for users. */
    private fun qaTimeshiftUrl(): String? =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra("tuneros.debug.timeshift_url")
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } else {
            null
        }

    /**
     * Debug-only timeshift check on scratch data; see [TimeshiftQa]. The window size is a debug extra, so a short run
     * fills it.
     */
    private fun runQaTimeshift(url: String) {
        val maxSegments = (intent?.getIntExtra("tuneros.debug.timeshift_segments", 6) ?: 6).coerceIn(2, 20)
        val report = java.io.File(filesDir, "tuneros-timeshift-report.txt")
        TimeshiftQa(
            report = report,
            ringDirectory = java.io.File(cacheDir, "timeshift-qa"),
            handler = android.os.Handler(mainLooper),
            playLocal = { uri, onFinished ->
                runQaPlayback(uri, report, "qa-timeshift-playback", reset = false, onFinished = onFinished)
            },
        ).run(url, maxSegments)
    }

    /**
     * Plays [url] with ExoPlayer into an off-screen ImageReader surface and waits for the first
     * rendered video frame. Writes its own report file; a 60 second deadline makes it FAIL.
     * [onFinished] receives PASS or FAIL once the check has ended.
     */
    private fun runQaPlayback(
        url: String,
        report: java.io.File = java.io.File(filesDir, "tuneros-playback-report.txt"),
        checkId: String = "qa-playback-first-frame",
        reset: Boolean = true,
        onFinished: ((String) -> Unit)? = null,
    ) {
        if (reset) report.writeText("RUNNING\n")
        val reader = android.media.ImageReader.newInstance(
            320, 240, android.graphics.ImageFormat.YUV_420_888, 3,
        )
        // Keep images flowing so rendering never stalls waiting for the app to release them.
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, null)
        val evidence = FirstResponseRecorder()
        val player = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(applicationContext, DefaultHttpDataSource.Factory())
                        .setTransferListener(evidence),
                ),
            )
            .build()
        var finished = false
        fun finish(state: String, detail: String) {
            if (finished) return
            finished = true
            report.appendText(state + "\t" + checkId + "\t" + detail + "\n")
            player.release()
            reader.close()
            onFinished?.invoke(state)
        }
        player.setVideoSurface(reader.surface)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                finish("PASS", "first video frame rendered")
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = StreamDoctor.describe(evidenceOf(error, evidence))
                finish("FAIL", "player error code ${error.errorCode}" + (if (detail.isEmpty()) "" else "; $detail"))
            }
        })
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        android.os.Handler(mainLooper).postDelayed(
            { finish("FAIL", "no first video frame within 60 seconds") }, 60_000,
        )
    }

    private fun isInvalidStartLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.invalid_start", false) == true

    private fun isSeriesCheckLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.series_check", false) == true

    private fun isParentalCheckLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.parental_check", false) == true

    /** Runs [onUnlocked] now when the session is unlocked, or after the PIN is entered and accepted. */
    private fun requireParentalPin(onUnlocked: () -> Unit) {
        if (parentalUnlocked) {
            onUnlocked()
            return
        }
        if (!parentalStore.hasPin()) {
            showStatus("This channel is restricted: set a parental PIN with the Parental PIN button first")
            return
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Parental PIN")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = input.text.toString()
                worker.execute {
                    val outcome = parentalStore.verify(pin)
                    runOnUiThread { handleParentalOutcome(outcome, onUnlocked) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleParentalOutcome(outcome: ParentalAttemptLimit.Outcome?, onUnlocked: () -> Unit) {
        when (outcome) {
            ParentalAttemptLimit.Outcome.Accepted -> {
                parentalUnlocked = true
                onUnlocked()
            }
            is ParentalAttemptLimit.Outcome.Rejected -> showStatus("Wrong PIN: ${outcome.attemptsLeft} tries left")
            is ParentalAttemptLimit.Outcome.Locked -> {
                val seconds = ((outcome.untilMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
                showStatus("Too many wrong PINs: try again in $seconds seconds")
            }
            null -> showStatus("No parental PIN is set")
        }
    }

    /** The Parental PIN button: sets the PIN the first time, and unlocks the session after that. */
    private fun openParentalPin() {
        if (parentalStore.hasPin()) {
            requireParentalPin { showStatus("Parental controls are unlocked for this session") }
            return
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4 to 8 digits"
        }
        AlertDialog.Builder(this)
            .setTitle("Set a parental PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (!ParentalPin.isValid(pin)) {
                    showStatus("The PIN must be 4 to 8 digits")
                    return@setPositiveButton
                }
                worker.execute {
                    parentalStore.setPin(pin)
                    runOnUiThread {
                        parentalUnlocked = true
                        showStatus("Parental PIN saved: restricted groups need it")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Restricts or releases a channel group by name, after the PIN unlocks the session. */
    private fun restrictGroupPrompt(restricted: Boolean) {
        requireParentalPin {
            val input = EditText(this).apply { hint = "Channel group name" }
            AlertDialog.Builder(this)
                .setTitle(if (restricted) "Restrict a channel group" else "Release a channel group")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    try {
                        parentalStore.restrict(input.text.toString(), restricted)
                        showStatus(if (restricted) "Group restricted" else "Group released")
                    } catch (error: IllegalArgumentException) {
                        showStatus("Enter a group name; up to ${ParentalPolicy.MAX_RESTRICTED_GROUPS} groups can be restricted")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun isAlarmLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.alarm", false) == true

    private fun isServiceStopLaunch(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.service_stop", false) == true

    private fun isDebugQaEnabled(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.qa", false) == true

    private fun runDebugUiFocusProbe() {
        if (isFinishing || isDestroyed) return
        val original = currentFocus
        // Android phone emulators start in touch mode; a TV remote key would
        // leave touch mode before moving focus. Model that transition here so
        // the probe tests D-pad behavior rather than touchscreen focus rules.
        var failed = 0L
        val enteredDpadMode = if (sourcesButton.isInTouchMode) {
            sourcesButton.requestFocusFromTouch()
        } else {
            sourcesButton.requestFocus()
        }
        if (!enteredDpadMode || !sourcesButton.hasFocus()) failed = failed or 1L
        if (!search.requestFocus() || !search.hasFocus()) failed = failed or 2L
        if (!hideNoGuide.requestFocus() || !hideNoGuide.hasFocus()) failed = failed or 4L
        // Search downward from an actually focused field, not from a view that
        // was previously focused and then replaced by the checkbox.
        if (!search.requestFocus() || !search.hasFocus()) failed = failed or 8L
        val next = search.focusSearch(View.FOCUS_DOWN)
        if (next == null || next === search || !next.isFocusable) {
            failed = failed or 16L
        } else if (!next.requestFocus() || !next.hasFocus()) {
            failed = failed or 32L
        }
        original?.requestFocus()
        observe(
            if (failed == 0L) OnDeviceSelfTest.Event.UI_FOCUS_PROBE_PASS
            else OnDeviceSelfTest.Event.UI_FOCUS_PROBE_FAIL,
            failed,
        )
        val emptyTimelineWorks = runCatching { debugEmptyTimelineNavigation() }.getOrDefault(false)
        observe(
            if (emptyTimelineWorks) OnDeviceSelfTest.Event.UI_EMPTY_TIMELINE_PASS
            else OnDeviceSelfTest.Event.UI_EMPTY_TIMELINE_FAIL,
        )
        val markerPassed = runCatching { debugClockMarkerRefresh() }.getOrDefault(false)
        observe(
            if (markerPassed) OnDeviceSelfTest.Event.UI_CLOCK_MARKER_PASS
            else OnDeviceSelfTest.Event.UI_CLOCK_MARKER_FAIL,
        )
        scheduleSelfTests()
    }

    /**
     * Debug-only drawing probe. Uses one tiny software bitmap to verify that
     * changing the now timestamp moves, then removes, the marker without
     * re-binding programmes or touching the real guide or player.
     */
    private fun debugClockMarkerRefresh(): Boolean {
        val timeline = ProgramTimelineView(
            context = this,
            onChannelSelected = {},
            onProgramFocused = { _, _ -> },
            onWindowShift = {},
        )
        timeline.bind(
            channel = Channel("qa-clock", "QA clock", "https://example.invalid/qa-clock"),
            entries = listOf(TimelineEntry(0L, 100L)),
            fromEpochSeconds = 0L,
            toEpochSeconds = 100L,
            nowEpochSeconds = 25L,
            preferredProgramStart = null,
        )
        timeline.layout(0, 0, 100, 24)
        val bitmap = Bitmap.createBitmap(100, 24, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val marker = Color.rgb(255, 116, 64)
            timeline.draw(canvas)
            if (bitmap.getPixel(25, 12) != marker) return false
            bitmap.eraseColor(Color.TRANSPARENT)
            timeline.refreshNow(75L)
            timeline.draw(canvas)
            if (bitmap.getPixel(75, 12) != marker ||
                bitmap.getPixel(25, 12) == marker
            ) return false
            bitmap.eraseColor(Color.TRANSPARENT)
            timeline.refreshNow(125L)
            timeline.draw(canvas)
            return bitmap.getPixel(75, 12) != marker
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Debug-only UI smoke test. It invokes the actual timeline View's key handlers
     * with a synthetic, empty EPG row. It neither starts playback nor changes the
     * real guide, focus, or recorder state.
     */
    private fun debugEmptyTimelineNavigation(): Boolean {
        var shiftedRight = 0
        var shiftedLeft = 0
        var playedChannelId: String? = null
        val channel = Channel("qa-empty", "QA empty guide", "https://example.invalid/qa")
        val timeline = ProgramTimelineView(
            context = this,
            onChannelSelected = { playedChannelId = it.id },
            onProgramFocused = { _, _ -> },
            onWindowShift = { direction ->
                if (direction > 0) shiftedRight++ else shiftedLeft++
            },
        )
        timeline.bind(
            channel = channel,
            entries = listOf(TimelineEntry(0, 1800)),
            fromEpochSeconds = 0,
            toEpochSeconds = 1800,
            nowEpochSeconds = 0,
            preferredProgramStart = null,
        )
        if (!timeline.isFocusable || timeline.isClickable) return false
        val rightKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val leftKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        val keysWorked = timeline.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, rightKey) &&
            timeline.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, leftKey) &&
            shiftedRight == 1 && shiftedLeft == 1 &&
            !timeline.performClick() && playedChannelId == null
        if (!keysWorked) return false

        // This same ListView row is now rebound to a different channel. The
        // pending LEFT key must not alter the new channel's selected programme.
        val second = Channel("qa-second", "QA second channel", "https://example.invalid/other")
        val older = GuideProgram("qa-second", "Older", 0L, 900L)
        val newer = GuideProgram("qa-second", "Newer", 900L, 1800L)
        timeline.bind(
            channel = second,
            entries = listOf(
                TimelineEntry(0L, 900L, older),
                TimelineEntry(900L, 1800L, newer),
            ),
            fromEpochSeconds = 0L,
            toEpochSeconds = 1800L,
            nowEpochSeconds = 0L,
            preferredProgramStart = 900L,
        )
        return timeline.contentDescription == "QA second channel, Newer" &&
            timeline.performClick() && playedChannelId == "qa-second"
    }

    private fun scheduleSelfTests() {
        // Only one probe pass at once; use the app's existing serial worker.
        if (worker.isShutdown) return
        // The isolated service-stop launch must not share the process with the main pass.
        if (isServiceStopLaunch() || isAlarmLaunch() || isInvalidStartLaunch() || qaPlaybackUrl() != null || qaImportUrls() != null || isKeyLaunch() || isRotationLaunch() || qaRecordUrl() != null || qaScheduledUrl() != null || isPickFolderLaunch() || isInternalStorageLaunch() || qaRecoveryMode() != null) return
        if (selfTestsQueued) {
            // An import completed while a previous pass was running: do not lose its results.
            selfTestsDirty = true
            return
        }
        selfTestsQueued = true
        val hasPlaylist = channels.isNotEmpty()
        val hasGuide = guide != null
        worker.execute {
            val report = runCatching {
                OnDeviceSelfTest.run(
                    applicationContext, hasPlaylist, hasGuide,
                    includeStress = isDebugQaEnabled(),
                )
            }
            runOnUiThread {
                selfTestsQueued = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                report.onSuccess { diagnosticsSummary.text = it.summary() }
                    .onFailure { diagnosticsSummary.text = "Autonomous QA: FAIL (runner)" }
                if (selfTestsDirty) {
                    selfTestsDirty = false
                    scheduleSelfTests()
                }
            }
        }
    }

    private fun observe(event: OnDeviceSelfTest.Event, value: Long = 0) {
        if (worker.isShutdown) return
        worker.execute {
            runCatching { OnDeviceSelfTest.observe(applicationContext, event, value) }
        }
    }

    private fun showDiagnostics() {
        val text = OnDeviceSelfTest.latestText(applicationContext)
        AlertDialog.Builder(this)
            .setTitle("Autonomous device diagnostics")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNegativeButton("Run again") { _, _ -> scheduleSelfTests() }
            .setNeutralButton("Share report") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { startActivity(Intent.createChooser(share, "Share TunerOS diagnostics")) }
                    .onFailure { showStatus("No compatible app available for sharing") }
            }
            .show()
    }

    private fun setSourcesVisible(visible: Boolean) {
        if (!::sourcePanel.isInitialized || !::sourcesButton.isInitialized) return
        sourcePanel.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        sourcesButton.text = if (visible) "Hide sources" else "Show sources"
    }

    private fun label(value: String, size: Int): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.WHITE)
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }
    private fun urlField(hintText: String, initial: String): EditText = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        setText(initial)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        textSize = 15f
        isFocusableInTouchMode = true
    }
    private fun button(value: String, onClick: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun loadPlaylist() = importPlaylist(playlistUrl.text.toString().trim(), true)

    private fun importPlaylist(url: String, saveOnSuccess: Boolean) {
        if (!isHttpUrl(url)) { showStatus("Enter a valid HTTP or HTTPS playlist URL"); return }
        val epoch = playlistEpoch.incrementAndGet()
        val startedAtMs = SystemClock.elapsedRealtime()
        showStatus("Importing channels…")
        worker.execute {
            val result = runCatching {
                downloader.open(url, 16L * 1024 * 1024).bufferedReader().use {
                    PlaylistParser.parseReport(it).also { parsed ->
                        require(parsed.channels.isNotEmpty()) { "Playlist contains no channels" }
                    }
                }
            }
            // Failed, empty or superseded imports must not overwrite a usable
            // encrypted source. Keep writes on the existing background worker.
            val saved = SourceRecoveryPolicy.mayCommit(
                result.isSuccess, epoch, playlistEpoch.get(),
            ) &&
                (!saveOnSuccess || runCatching {
                    secureSources.save("playlist", url)
                }.getOrDefault(false))
            runOnUiThread {
                if (epoch != playlistEpoch.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    channels = it.channels
                    val warnings = it.warnings + if (saved) emptyList() else listOf("URL not saved securely")
                    showStatus("Loaded ${it.channels.size} channels" + if (warnings.isEmpty()) "" else "; WARNING: " + warnings.joinToString("; "))
                    renderChannels()
                    observe(OnDeviceSelfTest.Event.PLAYLIST_IMPORTED, it.channels.size.toLong())
                    observe(OnDeviceSelfTest.Event.PLAYLIST_IMPORT_MILLIS,
                        SystemClock.elapsedRealtime() - startedAtMs)
                    scheduleSelfTests()
                }.onFailure {
                    observe(OnDeviceSelfTest.Event.PLAYLIST_IMPORT_FAILED)
                    observe(OnDeviceSelfTest.Event.PLAYLIST_IMPORT_MILLIS,
                        SystemClock.elapsedRealtime() - startedAtMs)
                    qaPlaylistFailure = it.javaClass.simpleName
                    showStatus("Playlist import failed: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    private fun loadGuide() = importGuide(guideUrl.text.toString().trim(), true)

    private fun importGuide(url: String, saveOnSuccess: Boolean) {
        if (!isHttpUrl(url)) { showStatus("Enter a valid HTTP or HTTPS XMLTV URL"); return }
        val epoch = guideEpoch.incrementAndGet()
        val startedAtMs = SystemClock.elapsedRealtime()
        showStatus("Importing guide…")
        worker.execute {
            val result = runCatching {
                downloader.open(url, 64L * 1024 * 1024).use {
                    GuideParser.parse(it, includeDescriptions = false)
                }.also { parsed ->
                    require(parsed.programs.isNotEmpty()) { "Guide contains no programmes" }
                    parsed.prepareIndex()
                }
            }
            val saved = SourceRecoveryPolicy.mayCommit(
                result.isSuccess, epoch, guideEpoch.get(),
            ) &&
                (!saveOnSuccess || runCatching {
                    secureSources.save("guide", url)
                }.getOrDefault(false))
            runOnUiThread {
                if (epoch != guideEpoch.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    guide = it
                    setSourcesVisible(false)
                    syncGuideClock()
                    val warnings = it.warnings + if (saved) emptyList() else listOf("URL not saved securely")
                    showStatus("Loaded ${it.programs.size} programs across ${it.channelNames.size} guide channels" +
                        if (warnings.isEmpty()) "" else "; WARNING: " + warnings.joinToString("; "))
                    renderChannels()
                    evaluateSeriesRules(it)
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORTED, it.programs.size.toLong())
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORT_MILLIS,
                        SystemClock.elapsedRealtime() - startedAtMs)
                    scheduleSelfTests()
                }.onFailure {
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORT_FAILED)
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORT_MILLIS,
                        SystemClock.elapsedRealtime() - startedAtMs)
                    qaGuideFailure = it.javaClass.simpleName
                    showStatus("Guide import failed: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    /** Long press on a channel row adds or removes a favorite. The guide and playback are unchanged. */
    private fun toggleFavorite(channel: Channel) {
        favorites = FavoriteChannels.toggle(favorites, channel.storageKey)
        val saved = favoriteStore.save(favorites)
        val added = channel.storageKey in favorites
        showStatus(
            (if (added) "Added " else "Removed ") + channel.name +
                (if (added) " to favorites" else " from favorites") +
                (if (saved) "" else "; not saved"),
        )
        renderChannels()
    }

    /** Channels with the user's guide mappings applied. Rebuilt only when the playlist or the mappings change. */
    private fun effectiveChannels(): List<Channel> {
        if (mappedSource !== channels || mappedFor !== guideMappings) {
            mappedChannels = GuideDoctor.applyMappings(channels, guideMappings)
            mappedSource = channels
            mappedFor = guideMappings
        }
        return mappedChannels
    }

    /** Guide Doctor, step 1: the channels that have no schedule. Choosing one opens its guide suggestions. */
    private fun openGuideDoctor() {
        val loadedGuide = guide
        if (loadedGuide == null) {
            showStatus("Load an XMLTV guide first. Guide Doctor compares channels with the guide.")
            return
        }
        val missing = GuideDoctor.channelsWithoutSchedule(effectiveChannels(), loadedGuide)
        if (missing.isEmpty()) {
            showStatus("Every channel already has a schedule.")
            return
        }
        val shown = missing.take(guideDoctorListLimit)
        val title = if (missing.size > shown.size) {
            "Channels without a schedule (${missing.size}; first ${shown.size})"
        } else {
            "Channels without a schedule (${missing.size})"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(shown.map { it.name }.toTypedArray()) { _, which -> openGuideSuggestions(shown[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Guide Doctor, step 2: guide channels that have a schedule and match the name, or removal of a saved mapping. */
    private fun openGuideSuggestions(channel: Channel) {
        val loadedGuide = guide ?: return
        val suggestions = GuideDoctor.suggestions(
            channel, loadedGuide.channelNames, loadedGuide::hasSchedule, limit = 8,
        )
        val hasSaved = guideMappings.containsKey(channel.storageKey)
        if (suggestions.isEmpty() && !hasSaved) {
            showStatus("No guide channel with a schedule matches ${channel.name}.")
            return
        }
        val removeLabel = if (hasSaved) listOf("Remove the saved guide mapping") else emptyList<String>()
        val labels = suggestions.map { it.displayName } + removeLabel
        AlertDialog.Builder(this)
            .setTitle("Guide for ${channel.name}")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < suggestions.size) {
                    setGuideMapping(channel, suggestions[which].guideId)
                } else {
                    clearGuideMapping(channel)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setGuideMapping(channel: Channel, guideId: String) {
        guideMappings = guideMappings + (channel.storageKey to guideId)
        val saved = guideMappingStore.save(guideMappings)
        showStatus("Mapped ${channel.name} to its guide channel" + (if (saved) "" else "; not saved"))
        renderChannels()
    }

    private fun clearGuideMapping(channel: Channel) {
        guideMappings = guideMappings - channel.storageKey
        val saved = guideMappingStore.save(guideMappings)
        showStatus("Removed the saved guide mapping for ${channel.name}" + (if (saved) "" else "; not saved"))
        renderChannels()
    }

    private fun renderChannels() {
        if (!::channelAdapter.isInitialized || !::search.isInitialized) return
        val query = search.text.toString().trim()
        val hide = hideNoGuide.isChecked
        val favoritesOnlyNow = favoritesOnly.isChecked
        val source = effectiveChannels()
        if (filteredSource !== source || filteredGuide !== guide ||
            filteredQuery != query || filteredHideNoGuide != hide ||
            filteredFavorites !== favorites || filteredFavoritesOnly != favoritesOnlyNow
        ) {
            filteredChannels = FavoriteChannels.filter(
                ChannelVisibility.filter(source, guide, query, hide),
                favorites,
                favoritesOnlyNow,
            )
            filteredSource = source
            filteredGuide = guide
            filteredQuery = query
            filteredHideNoGuide = hide
            filteredFavorites = favorites
            filteredFavoritesOnly = favoritesOnlyNow
        }
        val now = System.currentTimeMillis() / 1000
        if (guideWindowStart == 0L) {
            guideWindowStart = now - (now % guideStepSeconds)
        }
        updateTimeRail()
        channelAdapter.update(
            filteredChannels,
            guide,
            guideWindowStart,
            guideWindowStart + guideWindowSeconds,
            now,
            favorites,
        )
    }

    private fun shiftGuideWindow(deltaSeconds: Long) {
        guideWindowStart += deltaSeconds
        renderChannels()
    }

    private fun updateTimeRail() {
        if (!::timeRail.isInitialized || guideWindowStart == 0L) return
        val zone = ZoneId.systemDefault()
        val labels = (0..6).joinToString("     ") { halfHour ->
            timeFormatter.format(Instant.ofEpochSecond(guideWindowStart + halfHour * guideStepSeconds).atZone(zone))
        }
        timeRail.text = labels
    }

    private fun play(channel: Channel) {
        // A restricted group waits for the PIN; the channel that is playing keeps playing until it is accepted.
        if (ParentalPolicy.requiresPin(channel.group, parentalStore.restrictedGroups(), parentalUnlocked)) {
            requireParentalPin { play(channel) }
            return
        }
        stopTimeshift(restoreLive = false)
        val headers = mutableMapOf<String, String>()
        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { if (isHttpUrl(it)) headers["Referer"] = it }

        // Replacing the MediaItem on the same player avoids repeated decoder allocation.
        // A different provider header set requires a new data-source factory/player.
        val reusable = player?.takeIf {
            PlayerReusePolicy.canReuse(playerHeaders, headers, playingRecording = playingRecordingId != null)
        }
        val target = if (reusable != null) {
            reusable
        } else {
            releasePlayer()
            val evidence = FirstResponseRecorder()
            playbackEvidence = evidence
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(headers)
            val sourceFactory = DefaultMediaSourceFactory(
                DefaultDataSource.Factory(applicationContext, http).setTransferListener(evidence),
            )
            val created = ExoPlayer.Builder(applicationContext)
                .setMediaSourceFactory(sourceFactory)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                }
            player = created
            playerHeaders = headers.toMap()
            playerView.player = created
            created.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player === created) {
                        updateKeepScreenOn(isPlaying)
                        if (isPlaying) observe(OnDeviceSelfTest.Event.PLAYBACK_STARTED)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (player === created) {
                        clearPlaybackTimeout()
                        observe(OnDeviceSelfTest.Event.PLAYBACK_ERROR, error.errorCode.toLong())
                        showStatus(playbackFailureMessage(error, playbackEvidence))
                    }
                }
                override fun onRenderedFirstFrame() {
                    if (player === created) observe(OnDeviceSelfTest.Event.PLAYBACK_FIRST_FRAME)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== created) return
                    when (playbackState) {
                        Player.STATE_READY -> {
                            clearPlaybackTimeout()
                            observe(OnDeviceSelfTest.Event.PLAYBACK_READY)
                            showStatus("Playing: " + (activeChannelName ?: "channel"))
                        }
                        // A one-shot timeout also covers rebuffering *after* playback started.
                        Player.STATE_BUFFERING -> if (created.playWhenReady) armPlaybackTimeout(created)
                        Player.STATE_ENDED -> clearPlaybackTimeout()
                    }
                }
            })
            created
        }

        activeChannelName = channel.name
        val builder = MediaItem.Builder().setUri(channel.streamUrl)
        val path = channel.streamUrl.substringBefore('?').lowercase()
        if (path.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (path.endsWith(".mpd")) builder.setMimeType(MimeTypes.APPLICATION_MPD)
        observe(OnDeviceSelfTest.Event.PLAYBACK_REQUESTED)
        playbackEvidence?.reset()
        target.setMediaItem(builder.build())
        armPlaybackTimeout(target)
        target.prepare()
        target.playWhenReady = true
        showStatus("Connecting: ${channel.name}")
    }

    private fun recordFocusedProgram() {
        val channel = focusedChannel
        val program = focusedProgram
        if (channel == null || program == null) {
            showStatus("Focus a guide program first")
            return
        }
        val now = System.currentTimeMillis() / 1000
        if (now >= program.endEpochSeconds) {
            showStatus("That program has already ended")
            return
        }
        if (now < program.startEpochSeconds) {
            scheduleProgram(channel, program)
            return
        }
        val recordingId = channel.id.take(40)
            .replace(Regex("[^A-Za-z0-9._-]"), "_") +
            "-" + now + "-" + java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        val intent = Intent(this, DvrRecordingService::class.java).apply {
            action = DvrRecordingService.ACTION_START
            putExtra(DvrRecordingService.EXTRA_ID, recordingId)
            putExtra(DvrRecordingService.EXTRA_TITLE, program.title)
            putExtra(DvrRecordingService.EXTRA_CHANNEL_ID, channel.id)
            putExtra(DvrRecordingService.EXTRA_URL, channel.streamUrl)
            channel.userAgent?.let { putExtra(DvrRecordingService.EXTRA_USER_AGENT, it) }
            channel.referer?.let { putExtra(DvrRecordingService.EXTRA_REFERER, it) }
            putExtra(DvrRecordingService.EXTRA_END_EPOCH_SECONDS, program.endEpochSeconds)
        }
        startForegroundService(intent)
        showStatus("Recording: " + program.title)
    }


    /** What one scheduling request came to. Decided on the worker thread, reported on the UI thread. */
    private sealed class Reservation {
        class Scheduled(val mode: RecordingScheduler.Mode) : Reservation()
        class Conflict(val title: String) : Reservation()
        object AlreadyScheduled : Reservation()
        object NotReserved : Reservation()
        object SourceNotSaved : Reservation()
        class Failed(val reason: String) : Reservation()
    }

    /** At most this many programmes are reserved for one guide load, so a broad series cannot stall the worker. */
    private val maxSeriesReservations = 500

    private fun scheduleProgram(channel: Channel, program: GuideProgram) {
        worker.execute {
            val outcome = reserveRecording(channel, program)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showStatus(statusFor(outcome, program.title))
            }
        }
    }

    /** The status line for one reservation. */
    private fun statusFor(outcome: Reservation, title: String): String = when (outcome) {
        is Reservation.Conflict -> "Recording conflict with: " + outcome.title
        Reservation.AlreadyScheduled -> "Recording already scheduled"
        Reservation.NotReserved -> "Could not reserve DVR schedule"
        Reservation.SourceNotSaved -> "Could not securely save the recording source"
        is Reservation.Scheduled -> title + " · " + (
            if (outcome.mode == RecordingScheduler.Mode.EXACT) "scheduled"
            else "saved; enable Exact DVR timing before it can be armed")
        is Reservation.Failed -> "Could not schedule recording: " + outcome.reason
    }

    /**
     * Reserves one scheduled recording and arms it. Runs on the worker thread and shows nothing itself, so a manual
     * request and a series rule take exactly the same steps.
     */
    private fun reserveRecording(channel: Channel, program: GuideProgram): Reservation {
        val safeChannel = channel.id.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = "$safeChannel-${program.startEpochSeconds}"
        val sourceKey = "scheduled_recording.$id"
        val overlap = RecordingScheduleStore(this).use {
            it.findOverlap(id, program.startEpochSeconds, program.endEpochSeconds)
        }
        if (overlap != null) return Reservation.Conflict(overlap.title)
        val entry = RecordingScheduleEntry(
            id = id,
            title = program.title,
            channelId = channel.id,
            secureSourceKey = sourceKey,
            startEpochSeconds = program.startEpochSeconds,
            endEpochSeconds = program.endEpochSeconds,
            // A process death here must never rearm an unencrypted source.
            state = "PREPARING",
            exactAlarm = false,
            lastError = null,
        )
        val reservation = runCatching {
            RecordingScheduleStore(this).use { it.insertIfAbsent(entry) }
        }
        if (!reservation.getOrDefault(false)) {
            return if (reservation.isFailure) Reservation.NotReserved else Reservation.AlreadyScheduled
        }

        // Only the owner of the new row may write the deterministic source
        // key. A second scheduling request cannot overwrite its credentials.
        val saved = runCatching {
            ScheduledRecordingSecrets.save(
                secureSources, sourceKey,
                ScheduledRecordingSource(
                    channel.streamUrl, channel.userAgent, channel.referer,
                ),
            )
        }.getOrDefault(false)
        if (!saved) {
            runCatching {
                RecordingScheduleStore(this).use {
                    it.markFailed(id, "Unable to securely save recording source")
                }
                ScheduledRecordingSecrets.delete(secureSources, sourceKey)
            }
            return Reservation.SourceNotSaved
        }

        val result = runCatching {
            check(RecordingScheduleStore(this).use {
                it.markScheduledIfPreparing(id)
            }) { "DVR schedule reservation no longer available" }
            RecordingScheduler.schedule(this, entry.copy(state = "SCHEDULED"))
        }
        if (result.isFailure) {
            runCatching {
                RecordingScheduleStore(this).use {
                    it.markFailed(id,
                        result.exceptionOrNull()?.javaClass?.simpleName ?: "Schedule failure",
                    )
                }
                ScheduledRecordingSecrets.delete(secureSources, sourceKey)
            }
        }
        return result.fold(
            onSuccess = { Reservation.Scheduled(it) },
            onFailure = { Reservation.Failed(it.javaClass.simpleName) },
        )
    }

    /** Adds or removes the series rule for the focused programme's title on its guide channel. */
    private fun toggleSeriesRule() {
        val program = focusedProgram
        if (program == null) {
            showStatus("Focus a guide program first")
            return
        }
        val rule = SeriesRules.ruleFor(program.title, program.channelId)
        if (rule == null) {
            showStatus("This program has no title to match a series on")
            return
        }
        val snapshot = guide
        worker.execute {
            var added = false
            val message = runCatching {
                SeriesRuleStore(this).use { store ->
                    if (store.contains(rule)) {
                        if (store.remove(rule)) "Series removed: " + program.title
                        else "Could not remove the series rule"
                    } else {
                        added = store.add(rule, System.currentTimeMillis() / 1000)
                        if (added) "Series added: " + program.title
                        else "Series list is full or could not be saved"
                    }
                }
            }.getOrDefault("Could not save the series rule")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showStatus(message)
                if (added) evaluateSeriesRules(snapshot)
            }
        }
    }

    /**
     * Schedules every programme in [snapshot] that a series rule names, on the worker thread. Each one goes through the
     * same reservation as a manual request, so repeats and conflicts are handled the same way. A programme whose guide
     * channel is not mapped to a playlist channel is skipped. Call from the UI thread.
     */
    private fun evaluateSeriesRules(snapshot: GuideSnapshot?) {
        if (snapshot == null) return
        val channelByGuide = HashMap<String, Channel>()
        for (channel in effectiveChannels()) {
            val guideId = channel.guideId ?: continue
            channelByGuide.putIfAbsent(guideId, channel)
        }
        worker.execute {
            val rules = runCatching { SeriesRuleStore(this).use { it.all() } }.getOrDefault(emptyList())
            if (rules.isEmpty()) return@execute
            val planned = SeriesRules.plan(rules, snapshot.programs, System.currentTimeMillis() / 1000)
                .take(maxSeriesReservations)
            var scheduled = 0
            var skipped = 0
            var failed = 0
            for (program in planned) {
                val channel = channelByGuide[program.channelId]
                if (channel == null) {
                    skipped++
                    continue
                }
                when (reserveRecording(channel, program)) {
                    is Reservation.Scheduled -> scheduled++
                    is Reservation.Conflict, Reservation.AlreadyScheduled -> skipped++
                    else -> failed++
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showStatus("Series: $scheduled scheduled, $skipped already scheduled, in conflict or unmapped" +
                    (if (failed == 0) "" else ", $failed failed"))
            }
        }
    }


    private fun cancelFocusedSchedule() {
        val channel = focusedChannel
        val program = focusedProgram
        val now = System.currentTimeMillis() / 1000
        if (channel == null || program == null || program.startEpochSeconds <= now) {
            showStatus("Focus a future scheduled program first")
            return
        }
        val safeChannel = channel.id.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = "$safeChannel-${program.startEpochSeconds}"
        val sourceKey = "scheduled_recording.$id"
        worker.execute {
            val existing = RecordingScheduleStore(this).use { it.get(id) }
            if (existing == null || existing.state != "SCHEDULED") {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showStatus("No scheduled recording found for that program")
                }
                return@execute
            }
            RecordingScheduler.cancel(this, id)
            RecordingScheduleStore(this).use { it.delete(id) }
            ScheduledRecordingSecrets.delete(secureSources, sourceKey)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showStatus("Scheduled recording cancelled: " + program.title)
            }
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            showStatus("Exact DVR timing is already supported on this Android version")
            return
        }
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) {
            showStatus("Exact DVR timing is enabled")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            showStatus("Open Android Alarms & reminders settings for TunerOS")
        }
    }

    private fun stopRecording() {
        startService(Intent(this, DvrRecordingService::class.java).apply {
            action = DvrRecordingService.ACTION_STOP
        })
        showStatus("Stopping recording…")
    }

    private fun toggleRecordings() {
        if (showingRecordings) {
            showingRecordings = false
            focusedRecording = null
            channelList.adapter = channelAdapter
            syncGuideClock()
            timeRail.visibility = android.view.View.VISIBLE
            renderChannels()
            showStatus("Guide")
            return
        }
        showingRecordings = true
        focusedRecording = null
        channelList.adapter = recordingAdapter
        syncGuideClock()
        timeRail.visibility = android.view.View.GONE
        showStatus("Loading recordings…")
        refreshRecordingLibrary()
    }

    private fun refreshRecordingLibrary(statusOverride: String? = null) {
        worker.execute {
            val items = RecordingCatalog(this).use { it.recent(100) }
            runOnUiThread {
                if (isFinishing || isDestroyed || !showingRecordings) return@runOnUiThread
                recordingAdapter.update(items)
                if (focusedRecording != null && items.none { it.id == focusedRecording?.id }) {
                    focusedRecording = null
                }
                showStatus(
                    statusOverride
                        ?: if (items.isEmpty()) "No recordings yet" else "Recordings: " + items.size,
                )
            }
        }
    }

    private fun toggleFocusedRecordingProtection() {
        if (!showingRecordings) {
            showStatus("Open Recordings and focus an item first")
            return
        }
        val entry = focusedRecording ?: run {
            showStatus("Focus a recording first")
            return
        }
        if (entry.state == "RECORDING") {
            showStatus("Stop the active recording before changing protection")
            return
        }
        val protect = !entry.isProtected
        worker.execute {
            val updated = RecordingCatalog(this).use { it.setProtected(entry.id, protect) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (updated == 1) {
                    focusedRecording = entry.copy(isProtected = protect)
                    refreshRecordingLibrary(
                        entry.title + if (protect) " · Protected" else " · Protection removed",
                    )
                } else {
                    showStatus("Recording protection update failed")
                }
            }
        }
    }

    private fun deleteFocusedRecording() {
        if (!showingRecordings) {
            showStatus("Open Recordings and focus an item first")
            return
        }
        val entry = focusedRecording ?: run {
            showStatus("Focus a recording first")
            return
        }
        if (entry.state == "RECORDING") {
            showStatus("Stop the active recording before deleting it")
            return
        }
        if (entry.isProtected) {
            showStatus("Remove protection before deleting: " + entry.title)
            return
        }

        if (playingRecordingId == entry.id) releasePlayer()
        worker.execute {
            val result = RecordingLocationAccess.delete(this, entry.storageLocation)
            val mediaRemoved = result == RecordingLocationAccess.DeleteResult.DELETED ||
                result == RecordingLocationAccess.DeleteResult.MISSING
            val rowDeleted = if (mediaRemoved) {
                RecordingCatalog(this).use { it.delete(entry.id) == 1 }
            } else {
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    mediaRemoved && rowDeleted -> {
                        focusedRecording = null
                        refreshRecordingLibrary("Deleted recording: " + entry.title)
                    }
                    result == RecordingLocationAccess.DeleteResult.INVALID ->
                        showStatus("Refusing to delete a recording outside approved TunerOS storage")
                    else ->
                        showStatus("Could not delete recording: " + entry.title)
                }
            }
        }
    }

    private fun playRecording(entry: RecordingCatalogEntry) {
        stopTimeshift(restoreLive = false)
        if (entry.state != "COMPLETED") {
            showStatus("Only completed recordings can be played")
            return
        }
        showStatus("Opening recording: " + entry.title)
        worker.execute {
            val uri = RecordingLocationAccess.playableUri(this, entry.storageLocation)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (uri == null) {
                    showStatus("Recording is missing or access to its storage was lost")
                    return@runOnUiThread
                }
                startRecordingPlayback(entry, uri)
            }
        }
    }

    private fun startRecordingPlayback(entry: RecordingCatalogEntry, uri: Uri) {
        releasePlayer()
        val newPlayer = ExoPlayer.Builder(applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
        }
        player = newPlayer
        playingRecordingId = entry.id
        playerView.player = newPlayer
        newPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== newPlayer) return
                updateKeepScreenOn(isPlaying)
                if (isPlaying) observe(OnDeviceSelfTest.Event.RECORDING_PLAYBACK_STARTED)
            }
            override fun onRenderedFirstFrame() {
                if (player === newPlayer) {
                    observe(OnDeviceSelfTest.Event.RECORDING_PLAYBACK_FIRST_FRAME)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === newPlayer) {
                    clearPlaybackTimeout()
                    observe(OnDeviceSelfTest.Event.RECORDING_PLAYBACK_ERROR, error.errorCode.toLong())
                    showStatus(playbackFailureMessage(error, null))
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== newPlayer) return
                when (playbackState) {
                    Player.STATE_READY -> {
                        clearPlaybackTimeout()
                        observe(OnDeviceSelfTest.Event.RECORDING_PLAYBACK_READY)
                        showStatus("Playing recording: " + entry.title)
                    }
                    Player.STATE_BUFFERING -> if (newPlayer.playWhenReady) armPlaybackTimeout(newPlayer)
                    Player.STATE_ENDED -> clearPlaybackTimeout()
                }
            }
        })
        observe(OnDeviceSelfTest.Event.RECORDING_PLAYBACK_REQUESTED)
        newPlayer.setMediaItem(MediaItem.fromUri(uri))
        armPlaybackTimeout(newPlayer)
        newPlayer.prepare()
        newPlayer.playWhenReady = true
    }

    private fun chooseRecordingStorage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        runCatching {
            startActivityForResult(intent, recordingTreeRequestCode)
        }.onFailure {
            showStatus("This device does not provide a folder picker for external recording storage")
        }
    }

    private fun useInternalRecordingStorage() {
        if (recordingDestinations.clear()) {
            showStatus("Recording storage: TunerOS internal app storage")
        } else {
            showStatus("Could not change recording storage")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == recordingTreeRequestCode && (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.i(
                "TunerOSRecording",
                "folder picker returned code=$resultCode hasData=${data != null} flags=${data?.flags ?: 0}",
            )
        }
        if (requestCode != recordingTreeRequestCode || resultCode != RESULT_OK) return
        val resultData = data ?: run {
            showStatus("No recording folder was selected")
            return
        }
        val treeUri = resultData.data ?: run {
            showStatus("No recording folder was selected")
            return
        }
        val grantFlags = resultData.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        val requiredGrantFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (grantFlags and requiredGrantFlags != requiredGrantFlags) {
            showStatus("The selected recording folder must allow both reading and writing")
            return
        }

        val info = SafStorageInspector.inspect(this, treeUri)
        if (info == null) {
            showStatus(
                "Choose writable local shared, SD, or USB storage that reports free space",
            )
            return
        }
        if (info.availableBytes < RecordingPolicy.DEFAULT_MIN_FREE_BYTES) {
            showStatus("Selected storage does not have the required 512 MiB safety reserve")
            return
        }

        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(treeUri, grantFlags)
            recordingDestinations.saveTreeUri(treeUri)
        }.getOrDefault(false)
        if (!persisted) {
            showStatus("Could not retain access to the selected recording folder")
            return
        }

        val freeGiB = info.availableBytes / (1024.0 * 1024.0 * 1024.0)
        showStatus(
            "Recording storage selected: " +
                (info.rootTitle ?: "external storage") +
                " · " + String.format("%.1f GiB free", freeGiB),
        )
    }

    private fun clearPlaybackTimeout() {
        playbackTimeout?.let { uiHandler.removeCallbacks(it) }
        playbackTimeout = null
    }

    private fun armPlaybackTimeout(target: ExoPlayer) {
        clearPlaybackTimeout()
        val timeout = Runnable {
            if (player === target && target.playbackState != Player.STATE_READY) {
                val recorded = playingRecordingId != null
                observe(
                    if (recorded) OnDeviceSelfTest.Event.RECORDING_PLAYBACK_TIMEOUT
                    else OnDeviceSelfTest.Event.PLAYBACK_TIMEOUT,
                    30_000L,
                )
                showStatus("Stream Doctor: playback not ready after 30 seconds")
            }
        }
        playbackTimeout = timeout
        uiHandler.postDelayed(timeout, 30_000L)
    }

    private fun updateKeepScreenOn(isPlaying: Boolean) {
        if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Starts or stops a timeshift of the live channel in the player. While it runs, the player plays the recorded window. */
    private fun toggleTimeshift() {
        if (timeshift == null) startTimeshift() else stopTimeshift(restoreLive = true)
    }

    private fun startTimeshift() {
        val livePlayer = player ?: run {
            showStatus("Play a live channel before starting a timeshift")
            return
        }
        val liveUri = livePlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        if (liveUri == null || !(liveUri.startsWith("http://") || liveUri.startsWith("https://"))) {
            showStatus("Timeshift needs a live http or https channel")
            return
        }
        val headers = playerHeaders.orEmpty()
        val recorder = TimeshiftRecorder(
            directory = java.io.File(cacheDir, "timeshift"),
            maxSegments = timeshiftWindowSegments(),
            userAgent = headers["User-Agent"],
            referer = headers["Referer"],
        )
        timeshift = recorder
        timeshiftLiveUri = liveUri
        recorder.start(liveUri)
        showStatus("Timeshift starting: the picture switches to the recorded window in a few seconds")
        awaitTimeshiftStart(recorder, livePlayer, System.currentTimeMillis() + TimeshiftRecorder.STARTUP_DEADLINE_MILLIS)
    }

    /** Waits until a few segments are listed, then switches the same player to the ring's local playlist. */
    private fun awaitTimeshiftStart(recorder: TimeshiftRecorder, livePlayer: ExoPlayer, deadlineMillis: Long) {
        if (timeshift !== recorder || player !== livePlayer) return
        val state = recorder.state
        if (state is TimeshiftRecorder.State.Stopped || state is TimeshiftRecorder.State.Failed) {
            stopTimeshift(restoreLive = true)
            showStatus("Timeshift stopped: ${describeTimeshift(state)}")
            return
        }
        if (recorder.completedSegments < TimeshiftRecorder.STARTUP_SEGMENTS) {
            if (System.currentTimeMillis() > deadlineMillis) {
                stopTimeshift(restoreLive = true)
                showStatus("Timeshift did not start: no segments were recorded in time")
                return
            }
            uiHandler.postDelayed({ awaitTimeshiftStart(recorder, livePlayer, deadlineMillis) }, 500L)
            return
        }
        livePlayer.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(recorder.playlist).toString()))
        livePlayer.prepare()
        livePlayer.playWhenReady = true
        showStatus("Timeshift on: Back and Live move inside the recorded window")
    }

    /**
     * Ends the timeshift. With [restoreLive], the player first returns to the live channel, and then the recorder stops and
     * deletes its ring. Without it, the caller is about to replace the player's source and needs no restore.
     */
    private fun stopTimeshift(restoreLive: Boolean) {
        val recorder = timeshift ?: return
        val liveUri = timeshiftLiveUri
        timeshift = null
        timeshiftLiveUri = null
        val livePlayer = player
        if (restoreLive && livePlayer != null && liveUri != null && isShowingRing(livePlayer)) {
            livePlayer.setMediaItem(MediaItem.fromUri(liveUri))
            livePlayer.prepare()
            livePlayer.playWhenReady = true
        }
        recorder.stop()
    }

    private fun timeshiftBack() {
        val livePlayer = player
        if (timeshift == null || livePlayer == null || !isShowingRing(livePlayer)) {
            showStatus("Turn timeshift on first")
            return
        }
        livePlayer.seekTo(maxOf(0L, livePlayer.currentPosition - TimeshiftRecorder.BACK_MILLIS))
        showStatus("Back ${TimeshiftRecorder.BACK_MILLIS / 1000} seconds in the recorded window")
    }

    private fun timeshiftLive() {
        val livePlayer = player
        if (timeshift == null || livePlayer == null || !isShowingRing(livePlayer)) {
            showStatus("Turn timeshift on first")
            return
        }
        livePlayer.seekToDefaultPosition()
        showStatus("Live in the recorded window")
    }

    private fun isShowingRing(livePlayer: ExoPlayer?): Boolean =
        livePlayer?.currentMediaItem?.localConfiguration?.uri?.scheme == "file"

    private fun isLiveHttp(livePlayer: ExoPlayer?): Boolean {
        val scheme = livePlayer?.currentMediaItem?.localConfiguration?.uri?.scheme
        return scheme == "http" || scheme == "https"
    }

    private fun describeTimeshift(state: TimeshiftRecorder.State): String = when (state) {
        is TimeshiftRecorder.State.Stopped -> "stopped (${state.reason})"
        is TimeshiftRecorder.State.Failed -> "failed (${state.error})"
        else -> "running"
    }

    /** The window in segments. A debug extra can shorten it so a CI run fills it quickly; release builds ignore it. */
    private fun timeshiftWindowSegments(): Int {
        val requested = intent?.getIntExtra("tuneros.debug.timeshift_segments", 0) ?: 0
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable && requested > 0) requested.coerceIn(2, 900) else TimeshiftRecorder.DEFAULT_MAX_SEGMENTS
    }

    private fun releasePlayer() {
        stopTimeshift(restoreLive = false)
        clearPlaybackTimeout()
        updateKeepScreenOn(false)
        playerView.player = null
        player?.release()
        player = null
        playerHeaders = null
        playbackEvidence = null
        activeChannelName = null
        playingRecordingId = null
    }

    /**
     * No provider URL, response body or exception message is exposed. The detail is limited to an HTTP
     * status, a validated media type and a fixed transport label.
     */
    private fun playbackFailureMessage(error: PlaybackException, firstResponse: FirstResponseRecorder?): String {
        val diagnosis = StreamDoctor.diagnose(error.errorCode)
        val base = "Stream Doctor (${error.errorCode}): ${diagnosis.issue}. ${diagnosis.nextStep}"
        val detail = StreamDoctor.describe(evidenceOf(error, firstResponse))
        return if (detail.isEmpty()) base else "$base. Detail: $detail"
    }

    private fun showStatus(value: String) { status.text = value }

    private fun isHttpUrl(value: String): Boolean = runCatching { SourceDownloader.checkedUrl(value) }.isSuccess
    override fun onStart() {
        super.onStart()
        activityStarted = true
        syncGuideClock()
    }
    override fun onStop() {
        activityStarted = false
        uiHandler.removeCallbacks(clockRefresh)
        super.onStop()
        releasePlayer()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(
            STATE_SEARCH,
            if (::search.isInitialized) search.text.toString().take(256) else "",
        )
        outState.putLong(STATE_WINDOW, guideWindowStart)
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any? {
        val query = if (::search.isInitialized) search.text.toString() else ""
        if (channels.isEmpty() && guide == null && guideWindowStart == 0L && query.isEmpty()) {
            return null
        }
        return RetainedGuideState(
            channels = channels,
            guide = guide,
            guideWindowStart = guideWindowStart,
            searchQuery = query,
        )
    }

    override fun onDestroy() {
        playlistEpoch.incrementAndGet()
        guideEpoch.incrementAndGet()
        uiHandler.removeCallbacks(clockRefresh)
        uiHandler.removeCallbacks(searchRefresh)
        worker.shutdownNow()
        releasePlayer()
        super.onDestroy()
    }
}

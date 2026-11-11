package com.devtunasoft.tuneros

import android.app.Activity
import android.app.AlertDialog
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
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
import com.devtunasoft.tuneros.core.ChannelVisibility
import com.devtunasoft.tuneros.core.GuideParser
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.SourceDownloader
import com.devtunasoft.tuneros.core.StreamDoctor
import com.devtunasoft.tuneros.core.PlaylistParser
import com.devtunasoft.tuneros.core.RecordingPolicy
import com.devtunasoft.tuneros.core.TimelineEntry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** First playable TunerOS slice: HTTP M3U/XMLTV import, searchable TV guide, Media3 playback. */
class MainActivity : Activity() {
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
            if (guide != null && !isFinishing && !isDestroyed) renderChannels()
            uiHandler.postDelayed(this, 60_000)
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
    private var filteredChannels: List<Channel> = emptyList()
    private var player: ExoPlayer? = null
    // Reuse a single decoder for channels with identical provider request headers.
    private var playerHeaders: Map<String, String>? = null
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
        val retained = lastNonConfigurationInstance as? RetainedGuideState
        if (retained != null) {
            channels = retained.channels
            guide = retained.guide
            guideWindowStart = retained.guideWindowStart
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
        search = urlField("Search channels", retained?.searchQuery.orEmpty())
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
            addView(button("Cancel scheduled recording") { cancelFocusedSchedule() })
            addView(button("Exact DVR timing") { openExactAlarmSettings() })
            addView(button("Stop recording") { stopRecording() })
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
    }

    private fun isDebugQaEnabled(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("tuneros.debug.qa", false) == true

    private fun runDebugUiFocusProbe() {
        if (isFinishing || isDestroyed) return
        val original = currentFocus
        val controls = arrayOf(sourcesButton, search, hideNoGuide)
        val canFocus = controls.all { view -> view.requestFocus() && view.hasFocus() }
        val next = search.focusSearch(View.FOCUS_DOWN)
        val canNavigate = next != null && next !== search &&
            next.isFocusable && next.requestFocus() && next.hasFocus()
        original?.requestFocus()
        observe(
            if (canFocus && canNavigate) OnDeviceSelfTest.Event.UI_FOCUS_PROBE_PASS
            else OnDeviceSelfTest.Event.UI_FOCUS_PROBE_FAIL,
        )
        val emptyTimelineWorks = runCatching { debugEmptyTimelineNavigation() }.getOrDefault(false)
        observe(
            if (emptyTimelineWorks) OnDeviceSelfTest.Event.UI_EMPTY_TIMELINE_PASS
            else OnDeviceSelfTest.Event.UI_EMPTY_TIMELINE_FAIL,
        )
        scheduleSelfTests()
    }

    /**
     * Debug-only UI smoke test. It invokes the actual timeline View's key handlers
     * with a synthetic, empty EPG row. It neither starts playback nor changes the
     * real guide, focus, or recorder state.
     */
    private fun debugEmptyTimelineNavigation(): Boolean {
        var shiftedRight = 0
        var shiftedLeft = 0
        var played = false
        val channel = Channel("qa-empty", "QA empty guide", "https://example.invalid/qa")
        val timeline = ProgramTimelineView(
            context = this,
            onChannelSelected = { played = true },
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
        return timeline.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, rightKey) &&
            timeline.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, leftKey) &&
            shiftedRight == 1 && shiftedLeft == 1 &&
            !timeline.performClick() && !played
    }

    private fun scheduleSelfTests() {
        // Only one probe pass at once; use the app's existing serial worker.
        if (worker.isShutdown) return
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

    private fun loadPlaylist() {
        val url = playlistUrl.text.toString().trim()
        if (!isHttpUrl(url)) { showStatus("Enter a valid HTTP or HTTPS playlist URL"); return }
        val epoch = playlistEpoch.incrementAndGet()
        val startedAtMs = SystemClock.elapsedRealtime()
        showStatus("Importing channels…")
        worker.execute {
            val saved = secureSources.save("playlist", url)
            val result = runCatching { downloader.open(url, 16L * 1024 * 1024).bufferedReader().use { PlaylistParser.parseReport(it) } }
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
                    showStatus("Playlist import failed: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    private fun loadGuide() {
        val url = guideUrl.text.toString().trim()
        if (!isHttpUrl(url)) { showStatus("Enter a valid HTTP or HTTPS XMLTV URL"); return }
        val epoch = guideEpoch.incrementAndGet()
        val startedAtMs = SystemClock.elapsedRealtime()
        showStatus("Importing guide…")
        worker.execute {
            val saved = secureSources.save("guide", url)
            val result = runCatching {
                downloader.open(url, 64L * 1024 * 1024).use { GuideParser.parse(it, includeDescriptions = false) }.also { it.prepareIndex() }
            }
            runOnUiThread {
                if (epoch != guideEpoch.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    guide = it
                    setSourcesVisible(false)
                    val warnings = it.warnings + if (saved) emptyList() else listOf("URL not saved securely")
                    showStatus("Loaded ${it.programs.size} programs across ${it.channelNames.size} guide channels" +
                        if (warnings.isEmpty()) "" else "; WARNING: " + warnings.joinToString("; "))
                    renderChannels()
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORTED, it.programs.size.toLong())
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORT_MILLIS,
                        SystemClock.elapsedRealtime() - startedAtMs)
                    scheduleSelfTests()
                }.onFailure {
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORT_FAILED)
                    observe(OnDeviceSelfTest.Event.GUIDE_IMPORT_MILLIS,
                        SystemClock.elapsedRealtime() - startedAtMs)
                    showStatus("Guide import failed: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    private fun renderChannels() {
        if (!::channelAdapter.isInitialized || !::search.isInitialized) return
        val query = search.text.toString().trim()
        val hide = hideNoGuide.isChecked
        if (filteredSource !== channels || filteredGuide !== guide ||
            filteredQuery != query || filteredHideNoGuide != hide
        ) {
            filteredChannels = ChannelVisibility.filter(channels, guide, query, hide)
            filteredSource = channels
            filteredGuide = guide
            filteredQuery = query
            filteredHideNoGuide = hide
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
        val headers = mutableMapOf<String, String>()
        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { if (isHttpUrl(it)) headers["Referer"] = it }

        // Replacing the MediaItem on the same player avoids repeated decoder allocation.
        // A different provider header set requires a new data-source factory/player.
        val reusable = player?.takeIf {
            playingRecordingId == null && playerHeaders == headers
        }
        val target = if (reusable != null) {
            reusable
        } else {
            releasePlayer()
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(headers)
            val sourceFactory = DefaultMediaSourceFactory(
                DefaultDataSource.Factory(applicationContext, http),
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
                        showStatus(playbackFailureMessage(error))
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
        val lower = channel.streamUrl.substringBefore('?').lowercase()
        if (lower.endsWith(".mpd")) {
            showStatus("DASH recording is not enabled yet; playback is unaffected")
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


    private fun scheduleProgram(channel: Channel, program: GuideProgram) {
        val safeChannel = channel.id.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = "$safeChannel-${program.startEpochSeconds}"
        val sourceKey = "scheduled_recording.$id"
        worker.execute {
            val overlap = RecordingScheduleStore(this).use {
                it.findOverlap(id, program.startEpochSeconds, program.endEpochSeconds)
            }
            if (overlap != null) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showStatus("Recording conflict with: " + overlap.title)
                    }
                }
                return@execute
            }
            if (!ScheduledRecordingSecrets.save(
                    secureSources,
                    sourceKey,
                    ScheduledRecordingSource(channel.streamUrl, channel.userAgent, channel.referer),
                )
            ) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showStatus("Could not securely save the recording source")
                    }
                }
                return@execute
            }
            val entry = RecordingScheduleEntry(
                id = id,
                title = program.title,
                channelId = channel.id,
                secureSourceKey = sourceKey,
                startEpochSeconds = program.startEpochSeconds,
                endEpochSeconds = program.endEpochSeconds,
                state = "SCHEDULED",
                exactAlarm = false,
                lastError = null,
            )
            val result = runCatching {
                RecordingScheduleStore(this).use { it.upsert(entry) }
                RecordingScheduler.schedule(this, entry)
            }
            if (result.isFailure) {
                RecordingScheduleStore(this).use {
                    it.markFailed(id, result.exceptionOrNull()?.javaClass?.simpleName ?: "Schedule failure")
                }
                ScheduledRecordingSecrets.delete(secureSources, sourceKey)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { mode ->
                    val suffix = if (mode == RecordingScheduler.Mode.EXACT) {
                        "scheduled"
                    } else {
                        "saved; enable Exact DVR timing before it can be armed"
                    }
                    showStatus(program.title + " · " + suffix)
                }.onFailure {
                    showStatus("Could not schedule recording: " + it.javaClass.simpleName)
                }
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
            timeRail.visibility = android.view.View.VISIBLE
            renderChannels()
            showStatus("Guide")
            return
        }
        showingRecordings = true
        focusedRecording = null
        channelList.adapter = recordingAdapter
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
                    showStatus(playbackFailureMessage(error))
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

    private fun releasePlayer() {
        clearPlaybackTimeout()
        updateKeepScreenOn(false)
        playerView.player = null
        player?.release()
        player = null
        playerHeaders = null
        activeChannelName = null
        playingRecordingId = null
    }

    /** No provider URL, response body or exception message is exposed by diagnostics. */
    private fun playbackFailureMessage(error: PlaybackException): String {
        val diagnosis = StreamDoctor.diagnose(error.errorCode)
        return "Stream Doctor (${error.errorCode}): ${diagnosis.issue}. ${diagnosis.nextStep}"
    }

    private fun showStatus(value: String) { status.text = value }

    private fun isHttpUrl(value: String): Boolean = runCatching { SourceDownloader.checkedUrl(value) }.isSuccess
    override fun onStart() { super.onStart(); uiHandler.post(clockRefresh) }
    override fun onStop() {
        uiHandler.removeCallbacks(clockRefresh)
        super.onStop()
        releasePlayer()
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

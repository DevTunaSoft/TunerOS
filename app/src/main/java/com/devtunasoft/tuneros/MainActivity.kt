package com.devtunasoft.tuneros

import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.view.WindowManager
import android.view.KeyEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
import com.devtunasoft.tuneros.core.GuideParser
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.SourceDownloader
import com.devtunasoft.tuneros.core.PlaylistParser
import com.devtunasoft.tuneros.core.RecordingPolicy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** First playable TunerOS slice: HTTP M3U/XMLTV import, searchable TV guide, Media3 playback. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tuneros_alpha", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val playlistEpoch = AtomicInteger()
    private val guideEpoch = AtomicInteger()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val secureSources by lazy { SecureSourceStore(this) }
    private val recordingDestinations by lazy { RecordingDestinationStore(this) }
    private val recordingTreeRequestCode = 1701
    private val downloader = SourceDownloader()
    private var currentPrograms: Map<String, GuideProgram> = emptyMap()
    private val searchRefresh = Runnable { renderChannels() }
    private val clockRefresh = object : Runnable {
        override fun run() {
            val snapshot = guide
            if (snapshot != null) worker.execute {
                val updated = snapshot.currentProgramsAt(System.currentTimeMillis() / 1000)
                runOnUiThread {
                    if (guide === snapshot && !isFinishing && !isDestroyed) {
                        currentPrograms = updated
                        renderChannels()
                    }
                }
            }
            uiHandler.postDelayed(this, 60_000)
        }
    }
    private var channels: List<Channel> = emptyList()
    private var guide: GuideSnapshot? = null
    private var player: ExoPlayer? = null
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
    private lateinit var playlistUrl: EditText
    private lateinit var guideUrl: EditText
    private lateinit var search: EditText
    private lateinit var hideNoGuide: CheckBox
    private lateinit var status: TextView
    private lateinit var timeRail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        root.addView(label("TunerOS · Classic Cable Alpha", 22))
        playlistUrl = urlField("M3U playlist URL", "")
        root.addView(playlistUrl)
        root.addView(button("Load channels") { loadPlaylist() })
        guideUrl = urlField("XMLTV guide URL (optional)", "")
        root.addView(guideUrl)
        root.addView(button("Load guide") { loadGuide() })
        search = urlField("Search channels", "")
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
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1.1f))
        status = label("Enter an M3U URL to begin", 13)
        root.addView(status)
        root.addView(button("Record focused program") { recordFocusedProgram() })
        root.addView(button("Cancel focused scheduled recording") { cancelFocusedSchedule() })
        root.addView(button("Exact DVR timing") { openExactAlarmSettings() })
        root.addView(button("Stop recording") { stopRecording() })
        root.addView(button("Guide / Recordings") { toggleRecordings() })
        root.addView(button("Protect / Unprotect focused recording") { toggleFocusedRecordingProtection() })
        root.addView(button("Delete focused recording") { deleteFocusedRecording() })
        root.addView(button("Choose recording storage") { chooseRecordingStorage() })
        root.addView(button("Use internal recording storage") { useInternalRecordingStorage() })
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
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || showingRecordings) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { shiftGuideWindow(-guideStepSeconds); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { shiftGuideWindow(guideStepSeconds); true }
                    else -> false
                }
            }
        }
        root.addView(channelList, LinearLayout.LayoutParams(-1, 0, 1.0f))
        worker.execute {
            val playlist = runCatching { secureSources.load("playlist") }.getOrNull()
            val epg = runCatching { secureSources.load("guide") }.getOrNull()
            runCatching { RecordingScheduler.rearmPending(this) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (playlistUrl.text.isEmpty()) playlist?.let { playlistUrl.setText(it) }
                    if (guideUrl.text.isEmpty()) epg?.let { guideUrl.setText(it) }
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
                }.onFailure { showStatus("Playlist import failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun loadGuide() {
        val url = guideUrl.text.toString().trim()
        if (!isHttpUrl(url)) { showStatus("Enter a valid HTTP or HTTPS XMLTV URL"); return }
        val epoch = guideEpoch.incrementAndGet()
        showStatus("Importing guide…")
        worker.execute {
            val saved = secureSources.save("guide", url)
            val result = runCatching {
                downloader.open(url, 64L * 1024 * 1024).use { GuideParser.parse(it) }.also { it.prepareIndex() }
                    .let { snapshot -> snapshot to snapshot.currentProgramsAt(System.currentTimeMillis() / 1000) }
            }
            runOnUiThread {
                if (epoch != guideEpoch.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    guide = it.first
                    currentPrograms = it.second
                    val warnings = it.first.warnings + if (saved) emptyList() else listOf("URL not saved securely")
                    showStatus("Loaded ${it.first.programs.size} programs across ${it.first.channelNames.size} guide channels" +
                        if (warnings.isEmpty()) "" else "; WARNING: " + warnings.joinToString("; "))
                    renderChannels()
                }.onFailure { showStatus("Guide import failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun renderChannels() {
        if (!::channelAdapter.isInitialized || !::search.isInitialized) return
        val query = search.text.toString().trim()
        val visible = channels.asSequence()
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .filter { channel -> !hideNoGuide.isChecked || channel.guideId?.let { id -> guide?.hasSchedule(id) } == true }
            .take(500)
            .toList()
        if (guideWindowStart == 0L) {
            val now = System.currentTimeMillis() / 1000
            guideWindowStart = now - (now % guideStepSeconds)
        }
        updateTimeRail()
        channelAdapter.update(visible, guide, guideWindowStart, guideWindowStart + guideWindowSeconds)
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
        releasePlayer()
        val headers = mutableMapOf<String, String>()
        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { if (isHttpUrl(it)) headers["Referer"] = it }
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(headers)
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(applicationContext, http))
        val newPlayer = ExoPlayer.Builder(applicationContext).setMediaSourceFactory(sourceFactory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        }
        player = newPlayer
        playerView.player = newPlayer
        newPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateKeepScreenOn(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === newPlayer) showStatus("Playback problem on ${channel.name}: ${error.errorCodeName}")
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player === newPlayer && playbackState == Player.STATE_READY) {
                    showStatus("Playing: ${channel.name}")
                }
            }
        })
        val builder = MediaItem.Builder().setUri(channel.streamUrl)
        val url = channel.streamUrl.substringBefore('?').lowercase()
        if (url.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (url.endsWith(".mpd")) builder.setMimeType(MimeTypes.APPLICATION_MPD)
        newPlayer.setMediaItem(builder.build())
        newPlayer.prepare()
        newPlayer.playWhenReady = true
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
                updateKeepScreenOn(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === newPlayer) {
                    showStatus("Recording playback problem: " + error.errorCodeName)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player === newPlayer && playbackState == Player.STATE_READY) {
                    showStatus("Playing recording: " + entry.title)
                }
            }
        })
        newPlayer.setMediaItem(MediaItem.fromUri(uri))
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

    private fun updateKeepScreenOn(isPlaying: Boolean) {
        if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releasePlayer() {
        updateKeepScreenOn(false)
        playerView.player = null
        player?.release()
        player = null
        playingRecordingId = null
    }

    private fun showStatus(value: String) { status.text = value }

    private fun isHttpUrl(value: String): Boolean = runCatching { SourceDownloader.checkedUrl(value) }.isSuccess
    override fun onStart() { super.onStart(); uiHandler.post(clockRefresh) }
    override fun onStop() {
        uiHandler.removeCallbacks(clockRefresh)
        super.onStop()
        releasePlayer()
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

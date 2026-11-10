package com.devtunasoft.tuneros

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var guideWindowStart: Long = 0
    private val guideWindowSeconds = 3 * 3600L
    private val guideStepSeconds = 30 * 60L
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private lateinit var playerView: PlayerView
    private lateinit var channelList: ListView
    private lateinit var channelAdapter: CableGuideAdapter
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
        timeRail = label("", 14).apply {
            setBackgroundColor(Color.rgb(18, 31, 50))
            setPadding(dp(194), dp(8), dp(8), dp(8))
        }
        root.addView(timeRail)
        channelAdapter = CableGuideAdapter(
            this,
            onChannelSelected = ::play,
            onProgramFocused = { channel, program ->
                showStatus(channel.name + (program?.let { " · " + it.title } ?: ""))
            },
        )
        channelList = ListView(this).apply {
            adapter = channelAdapter
            isFocusable = true
            dividerHeight = 0
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
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

    private fun updateKeepScreenOn(isPlaying: Boolean) {
        if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releasePlayer() {
        updateKeepScreenOn(false)
        playerView.player = null
        player?.release()
        player = null
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

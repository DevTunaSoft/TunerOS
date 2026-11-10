package com.devtunasoft.tuneros

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
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
    private lateinit var playerView: PlayerView
    private lateinit var channelList: ListView
    private lateinit var channelAdapter: ChannelListAdapter
    private lateinit var playlistUrl: EditText
    private lateinit var guideUrl: EditText
    private lateinit var search: EditText
    private lateinit var hideNoGuide: CheckBox
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.rgb(13, 21, 34))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
        }
        setContentView(root)
        root.addView(label("TunerOS · Classic Cable Alpha", 22))
        playlistUrl = urlField("M3U playlist URL", secureSources.load("playlist").orEmpty())
        root.addView(playlistUrl)
        root.addView(button("Load channels") { loadPlaylist() })
        guideUrl = urlField("XMLTV guide URL (optional)", secureSources.load("guide").orEmpty())
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
        channelAdapter = ChannelListAdapter(this)
        channelList = ListView(this).apply {
            adapter = channelAdapter
            isFocusable = true
            onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                play(channelAdapter.getItem(position))
            }
        }
        root.addView(channelList, LinearLayout.LayoutParams(-1, 0, 1.0f))
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
        setPadding(4, 3, 4, 3)
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
        val saved = secureSources.save("playlist", url)
        val epoch = playlistEpoch.incrementAndGet()
        showStatus("Importing channels…")
        worker.execute {
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
        val saved = secureSources.save("guide", url)
        val epoch = guideEpoch.incrementAndGet()
        showStatus("Importing guide…")
        worker.execute {
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
        channelAdapter.update(visible, currentPrograms)
    }

    private fun play(channel: Channel) {
        playerView.player = null
        player?.release()
        val headers = mutableMapOf<String, String>()
        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { if (isHttpUrl(it)) headers["Referer"] = it }
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(headers)
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val newPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).build()
        player = newPlayer
        playerView.player = newPlayer
        newPlayer.addListener(object : Player.Listener {
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

    private fun showStatus(value: String) { status.text = value }

    private fun isHttpUrl(value: String): Boolean = runCatching { SourceDownloader.checkedUrl(value) }.isSuccess
    override fun onStart() { super.onStart(); uiHandler.post(clockRefresh) }
    override fun onStop() {
        uiHandler.removeCallbacks(clockRefresh)
        super.onStop()
        player?.pause()
    }
    override fun onDestroy() {
        playlistEpoch.incrementAndGet()
        guideEpoch.incrementAndGet()
        uiHandler.removeCallbacks(clockRefresh)
        uiHandler.removeCallbacks(searchRefresh)
        worker.shutdownNow()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

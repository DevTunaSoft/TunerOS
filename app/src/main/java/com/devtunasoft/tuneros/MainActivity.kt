package com.devtunasoft.tuneros

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.devtunasoft.tuneros.core.PlaylistParser
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** First playable TunerOS slice: HTTP M3U/XMLTV import, searchable TV guide, Media3 playback. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tuneros_alpha", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val playlistEpoch = AtomicInteger()
    private val guideEpoch = AtomicInteger()
    private var channels: List<Channel> = emptyList()
    private var guide: GuideSnapshot? = null
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var rows: LinearLayout
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
        playlistUrl = urlField("M3U playlist URL", prefs.getString("playlist", "").orEmpty())
        root.addView(playlistUrl)
        root.addView(button("Load channels") { loadPlaylist() })
        guideUrl = urlField("XMLTV guide URL (optional)", prefs.getString("guide", "").orEmpty())
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
        val scroller = ScrollView(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(rows)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1.0f))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderChannels()
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
        if (!isHttpUrl(url)) { showStatus("Enter an HTTP or HTTPS playlist URL"); return }
        prefs.edit().putString("playlist", url).apply()
        val epoch = playlistEpoch.incrementAndGet()
        showStatus("Importing channels…")
        worker.execute {
            val result = runCatching { boundedDownload(url, 16L * 1024 * 1024).bufferedReader().use { PlaylistParser.parse(it) } }
            runOnUiThread {
                if (epoch != playlistEpoch.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    channels = it
                    showStatus("Loaded ${it.size} channels")
                    renderChannels()
                }.onFailure { showStatus("Playlist import failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun loadGuide() {
        val url = guideUrl.text.toString().trim()
        if (!isHttpUrl(url)) { showStatus("Enter an HTTP or HTTPS XMLTV URL"); return }
        prefs.edit().putString("guide", url).apply()
        val epoch = guideEpoch.incrementAndGet()
        showStatus("Importing guide…")
        worker.execute {
            val result = runCatching { boundedDownload(url, 64L * 1024 * 1024).use(GuideParser::parse) }
            runOnUiThread {
                if (epoch != guideEpoch.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    guide = it
                    showStatus("Loaded ${it.programs.size} programs across ${it.channelNames.size} guide channels")
                    renderChannels()
                }.onFailure { showStatus("Guide import failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun renderChannels() {
        if (!::rows.isInitialized || !::search.isInitialized) return
        rows.removeAllViews()
        val query = search.text.toString().trim()
        val now = System.currentTimeMillis() / 1000
        val visible = channels.asSequence()
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .filter { !hideNoGuide.isChecked || (it.guideId != null && guide?.now(it.guideId, now) != null) }
            .take(500)
            .toList()
        for (channel in visible) {
            val current = channel.guideId?.let { guide?.now(it, now) }
            val caption = if (current == null) "No current EPG" else current.title
            val row = button("${channel.name}  •  $caption") { play(channel) }
            row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            row.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            row.isFocusable = true
            rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (channels.size > 500) rows.addView(label("Showing the first 500 matches; use search to narrow the guide.", 12))
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

    /** Bounded import includes a decompressed-byte cap. Never open arbitrary non-HTTP schemes. */
    private fun boundedDownload(source: String, maxBytes: Long): InputStream {
        if (!isHttpUrl(source)) throw IllegalArgumentException("Unsupported URL scheme")
        var address = source
        repeat(5) {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IllegalArgumentException("Redirect without location")
                val next = URL(URL(address), location).toString()
                connection.disconnect()
                if (!isHttpUrl(next)) throw IllegalArgumentException("Unsafe redirect")
                address = next
                return@repeat
            }
            if (code != 200) { connection.disconnect(); throw IllegalStateException("HTTP $code") }
            val raw = connection.inputStream
            val bytes = if (connection.contentEncoding.equals("gzip", true) || address.endsWith(".gz", true)) GZIPInputStream(raw) else raw
            return object : FilterInputStream(bytes) {
                private var size = 0L
                override fun read(): Int {
                    val n = super.read()
                    if (n != -1) checkSize(1)
                    return n
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = super.read(buffer, offset, length)
                    if (n > 0) checkSize(n)
                    return n
                }
                private fun checkSize(n: Int) {
                    size += n
                    if (size > maxBytes) throw IllegalArgumentException("Import exceeds size limit")
                }
                override fun close() { super.close(); connection.disconnect() }
            }
        }
        throw IllegalArgumentException("Too many redirects")
    }
    private fun isHttpUrl(value: String) = value.startsWith("https://", true) || value.startsWith("http://", true)
    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() {
        playlistEpoch.incrementAndGet()
        guideEpoch.incrementAndGet()
        worker.shutdownNow()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

package com.devtunasoft.tuneros

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.AssetManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

/**
 * MV-03: compiled, deterministic acceptance for the existing two-tile multiview policy in the
 * actual app UI. This is a platform-Instrumentation runner, so it adds no test dependency and
 * touches no application source. The real MainActivity is launched with 127.0.0.1-only scratch
 * fixtures; its own visible labels, tile views and tile-player attachments are inspected after
 * real UI clicks. No provider URL, credential, first-frame claim or human remote operation is
 * involved. Platform audio output itself is not measurable here: the single-audio assertion is
 * about the app's one audible tile label and its one attached tile player.
 */
class MultiviewUiAcceptanceInstrumentation : Instrumentation() {

    private data class Outcome(val name: String, val passed: Boolean, val detail: String)

    private data class TileState(
        val slot: Int,
        val description: String,
        val parentVisible: Boolean,
        val playerAttached: Boolean?,
    ) {
        val shown: Boolean get() = !description.endsWith(", empty")
        val audible: Boolean get() = description.endsWith(", audible")
        val muted: Boolean get() = description.contains(", muted")
    }

    private data class UiState(
        val tiles: List<TileState>,
        val texts: List<String>,
        val button: String,
    ) {
        val shownTiles: List<TileState> get() = tiles.filter { it.shown }
        fun hasText(value: String): Boolean = texts.any { it == value }
        fun hasStatusContaining(value: String): Boolean = texts.any { it.contains(value) }
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // The hidden platform run() hook is not reliably dispatched, so run on our own bounded
        // thread and finish() explicitly. A stuck check is bounded by the shell runner's timeout.
        Thread({ runChecks() }, "mv03-ui-runner").apply { isDaemon = false }.start()
    }

    private fun runChecks() {
        val outcomes = ArrayList<Outcome>()
        var fixture: LoopbackFixture? = null
        var activity: Activity? = null
        try {
            fixture = LoopbackFixture(context.assets, windowAnchor())
            val loopback = fixture
            val report = File(targetContext.filesDir, REPORT_NAME)
            if (report.isFile && !report.delete()) throw AssertionError("stale MV-03 report could not be removed")

            activity = launch(loopback)
            val first = activity
            waitForImport()
            val imported = onUi { channelNames(first) }

            outcomes += execute("mv03-loopback-import") {
                require(imported == CHANNELS) { "imported channel cells=$imported, expected $CHANNELS" }
                "three scratch channels imported from 127.0.0.1"
            }

            clickButton(first, "Multiview: off", "Multiview: on")
            selectChannel(first, CHANNELS[0])
            await(10_000L, "first tile visible") {
                onUi { uiState(first) }.shownTiles.any { it.description.startsWith("Video tile 1, audible") }
            }
            selectChannel(first, CHANNELS[1])
            await(10_000L, "second tile visible") {
                onUi { uiState(first) }.shownTiles.size == 2
            }
            val twoTiles = onUi { uiState(first) }

            outcomes += execute("mv03-two-bounded-tiles-shown") {
                val shown = twoTiles.shownTiles
                require(twoTiles.button == "Multiview: on") { "button=${twoTiles.button}, expected Multiview: on" }
                require(shown.size == 2) { "shown tiles=${shown.size}, expected exactly 2" }
                require(shown.map { it.slot } == listOf(1, 2)) { "tile slots=${shown.map { it.slot }}" }
                require(shown.count { it.audible } == 1) { "audible tile count=${shown.count { it.audible }}" }
                require(shown.count { it.muted } == 1) { "muted tile count=${shown.count { it.muted }}" }
                require(shown.all { it.parentVisible }) { "a tile row or tile view is not visible" }
                "tile 1 audible; tile 2 muted; both visible"
            }

            selectChannel(first, CHANNELS[2])
            await(10_000L, "third tile capacity refusal") {
                onUi { uiState(first) }.hasStatusContaining("Two tiles are open")
            }
            val refused = onUi { uiState(first) }

            outcomes += execute("mv03-third-tile-refused") {
                require(refused.shownTiles.size == 2) { "shown tiles=${refused.shownTiles.size} after third selection" }
                require(refused.shownTiles.count { it.audible } == 1) { "audio changed on a refused third tile" }
                require(refused.shownTiles.none { it.slot == 3 }) { "a third tile view became visible" }
                "third channel refused; two tiles and one audible tile unchanged"
            }

            clickTile(first, 2)
            await(10_000L, "single audible focus switch") {
                onUi { uiState(first) }.hasStatusContaining("Sound switched to the focused tile")
            }
            val focused = onUi { uiState(first) }

            outcomes += execute("mv03-single-audible-tile") {
                val shown = focused.shownTiles
                require(shown.size == 1) { "shown tiles=${shown.size} after focus, expected one" }
                require(shown.single().audible) { "the remaining tile is not the audible one" }
                require(shown.none { it.muted }) { "a muted tile remained after focus" }
                "focus promoted one tile and released the other; exactly one audible"
            }

            clickButton(first, "Multiview: on", "Multiview: off")
            await(10_000L, "tile row hidden") {
                onUi { uiState(first) }.tiles.none { it.shown }
            }
            val released = onUi { uiState(first) }

            outcomes += execute("mv03-tiles-released-cleanly") {
                require(released.button == "Multiview: off") { "button=${released.button}" }
                require(released.shownTiles.isEmpty()) { "visible tiles remained after opt-out" }
                require(released.tiles.size == 2) { "tile views=${released.tiles.size}, expected the original two" }
                require(released.tiles.all { !it.parentVisible }) { "tile row stayed visible after opt-out" }
                require(released.tiles.all { it.playerAttached == false }) {
                    "a tile player is still attached: ${released.tiles.map { it.playerAttached }}"
                }
                "tile row hidden and both tile players detached"
            }

            finishActivity(first)
            activity = null
            val file = File(targetContext.filesDir, "tuneros-import-report.txt")
            if (file.isFile && !file.delete()) throw AssertionError("stale import report could not be removed")
            val relaunched = launch(loopback)
            activity = relaunched
            waitForImport()
            await(10_000L, "relaunch state") {
                onUi { uiState(relaunched) }.button == "Multiview: off"
            }
            val fresh = onUi { uiState(relaunched) }

            outcomes += execute("mv03-relaunch-has-no-tiles") {
                require(fresh.shownTiles.isEmpty()) { "tiles survived an activity relaunch" }
                require(fresh.tiles.all { it.playerAttached == false }) { "a tile player survived a relaunch" }
                "fresh MainActivity is Classic Cable with no attached tile player"
            }
        } catch (error: Throwable) {
            outcomes += Outcome("mv03-runner", false, error.message ?: error.javaClass.simpleName)
        } finally {
            activity?.let { finishActivity(it) }
            fixture?.close()
        }

        val failures = outcomes.count { !it.passed }
        val report = buildString {
            append("MV-03 two-tile multiview UI acceptance | device=").append(Build.MODEL)
            append(" | checks=").append(outcomes.size).append(" | failures=").append(failures).append('\n')
            for (outcome in outcomes) {
                append(if (outcome.passed) "PASS\t" else "FAIL\t")
                append(outcome.name).append('\t').append(outcome.detail).append('\n')
            }
            append("SUMMARY\t").append(outcomes.size - failures).append(" PASS\t").append(failures).append(" FAIL\n")
        }
        File(targetContext.filesDir, REPORT_NAME).writeText(report)
        finish(
            if (failures == 0 && outcomes.size == REQUIRED_CHECKS) Activity.RESULT_OK else 1,
            Bundle().apply {
                putInt("mv03_checks", outcomes.size)
                putInt("mv03_failures", failures)
            },
        )
    }

    private fun execute(name: String, body: () -> String): Outcome = try {
        Outcome(name, true, body())
    } catch (error: Throwable) {
        Outcome(name, false, error.message ?: error.javaClass.simpleName)
    }

    private fun launch(loopback: LoopbackFixture): Activity {
        val intent = Intent().setClassName(targetContext, TARGET_ACTIVITY)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra("tuneros.debug.import_playlist_url", loopback.playlistUrl)
        intent.putExtra("tuneros.debug.import_guide_url", loopback.guideUrl)
        return startActivitySync(intent)
    }

    private fun finishActivity(activity: Activity) {
        onUi { activity.finish() }
        waitForIdleSync()
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onUi { activity.isFinishing || activity.isDestroyed }) return
            SystemClock.sleep(100)
        }
    }

    private fun waitForImport() {
        val report = File(targetContext.filesDir, "tuneros-import-report.txt")
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val text = if (report.isFile) {
                val bytes = ByteArray(MAX_REPORT_BYTES)
                val length = report.inputStream().use { it.read(bytes) }
                if (length > 0) String(bytes, 0, length, StandardCharsets.UTF_8) else ""
            } else {
                ""
            }
            if (text.contains("PASS\tqa-loopback-import")) return
            if (text.contains("FAIL\tqa-loopback-import")) throw AssertionError("scratch import failed in the app")
            SystemClock.sleep(100)
        }
        throw AssertionError("scratch import report timed out")
    }

    /** UI-thread only: exact channel cell labels, taken from the guide list, not the status label. */
    private fun channelNames(activity: Activity): List<String> {
        val list = findViews(activity.window.decorView).firstOrNull {
            it.javaClass.name == "com.devtunasoft.tuneros.GuideRowList"
        } as? ViewGroup ?: return emptyList()
        val result = ArrayList<String>()
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) as? ViewGroup ?: continue
            for (child in 0 until row.childCount) {
                val cell = row.getChildAt(child) as? TextView ?: continue
                cell.text?.toString()?.let { result += it }
            }
        }
        return result
    }

    private fun selectChannel(activity: Activity, name: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val clicked = onUi {
                val list = findViews(activity.window.decorView).firstOrNull {
                    it.javaClass.name == "com.devtunasoft.tuneros.GuideRowList"
                } as? ViewGroup
                val cell = list?.let { findViews(it).filterIsInstance<TextView>().firstOrNull { it.text?.toString() == name } }
                if (cell == null) false else { cell.performClick(); true }
            }
            if (clicked) return
            SystemClock.sleep(100)
        }
        throw AssertionError("channel cell $name was not clickable")
    }

    private fun clickButton(activity: Activity, before: String, after: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val clicked = onUi {
                val button = findViews(activity.window.decorView).filterIsInstance<TextView>()
                    .firstOrNull { it.text?.toString() == before }
                if (button == null) false else { button.performClick(); true }
            }
            if (clicked) {
                await(10_000L, "button $after") { onUi { uiState(activity) }.button == after }
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("button $before was not clickable")
    }

    private fun clickTile(activity: Activity, slot: Int) {
        val clicked = onUi {
            val muted = findViews(activity.window.decorView).firstOrNull { view ->
                view.contentDescription?.toString()?.startsWith("Video tile $slot, muted") == true
            }
            if (muted == null) false else { muted.performClick(); true }
        }
        require(clicked) { "muted tile $slot was not clickable" }
    }

    /** UI-thread only. Reads the live compiled view tree; no screenshot or synthetic label is used. */
    private fun uiState(activity: Activity): UiState {
        val views = findViews(activity.window.decorView)
        val tiles = ArrayList<TileState>()
        for (view in views) {
            val description = view.contentDescription?.toString().orEmpty()
            if (!description.startsWith("Video tile ")) continue
            val slot = description.substringAfter("Video tile ").substringBefore(',').toIntOrNull() ?: continue
            val parent = view.parent as? View
            tiles += TileState(
                slot = slot,
                description = description,
                parentVisible = parent?.visibility == View.VISIBLE && view.visibility == View.VISIBLE,
                playerAttached = playerAttached(view),
            )
        }
        val buttons = views.filterIsInstance<TextView>().mapNotNull { it.text?.toString() }
        return UiState(
            tiles = tiles.sortedBy { it.slot },
            texts = buttons,
            button = buttons.firstOrNull { it == "Multiview: on" || it == "Multiview: off" }.orEmpty(),
        )
    }

    /** Reflection keeps this runner dependency-free while still proving a player was detached. */
    private fun playerAttached(view: View): Boolean? = try {
        val accessor = view.javaClass.getMethod("getPlayer")
        accessor.isAccessible = true
        accessor.invoke(view) != null
    } catch (_: Throwable) {
        null
    }

    private fun findViews(root: View): List<View> {
        val result = ArrayList<View>()
        fun walk(view: View) {
            result += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return result
    }

    private fun <T> onUi(block: () -> T): T {
        val reference = AtomicReference<T>()
        runOnMainSync { reference.set(block()) }
        return reference.get()
    }

    private fun await(timeoutMillis: Long, label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("$label timed out")
    }

    private fun windowAnchor(): Long {
        val seconds = System.currentTimeMillis() / 1_000L
        return seconds - seconds % 1_800L
    }

    /** Bounded 127.0.0.1 fixture server. It answers only known scratch paths, at most 64 requests. */
    private class LoopbackFixture(assets: AssetManager, anchor: Long) {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        private val requestedPaths = Collections.synchronizedList(ArrayList<String>())
        @Volatile
        private var running = true
        private val thread: Thread

        val playlistUrl: String get() = "http://127.0.0.1:$port/playlist.m3u"
        val guideUrl: String get() = "http://127.0.0.1:$port/guide.xml"

        private val playlist: String
        private val guide: String

        init {
            server.soTimeout = ACCEPT_TIMEOUT_MILLIS
            val values = mapOf(
                "PORT" to port.toString(),
                "T_MINUS" to xmlTime(anchor - HALF_HOUR_SECONDS),
                "T_NOW" to xmlTime(anchor),
                "T_PLUS" to xmlTime(anchor + HALF_HOUR_SECONDS),
            )
            playlist = substitute(assets.read("mv03/playlist.m3u.tmpl"), values)
            guide = substitute(assets.read("mv03/guide.xml.tmpl"), values)
            thread = Thread({ serveLoop() }, "mv03-fixture").apply { isDaemon = true }
            thread.start()
        }

        fun close() {
            running = false
            runCatching { server.close() }
            thread.join(JOIN_TIMEOUT_MILLIS)
        }

        private fun serveLoop() {
            while (running) {
                try {
                    serve(server.accept())
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    if (!running) return
                    return
                }
            }
        }

        private fun serve(socket: java.net.Socket) {
            socket.use { connection ->
                connection.soTimeout = READ_TIMEOUT_MILLIS
                val line = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
                    .readLine() ?: return
                val path = line.split(' ').getOrElse(1) { "" }
                synchronized(requestedPaths) {
                    if (requestedPaths.size < MAX_REQUESTS) requestedPaths += path
                }
                val (status, contentType, body) = when (path) {
                    "/playlist.m3u" -> Triple("200 OK", "application/vnd.apple.mpegurl", playlist)
                    "/guide.xml" -> Triple("200 OK", "application/xml", guide)
                    // Scratch stream paths deliberately 404: this suite checks UI policy, not decoding.
                    else -> Triple("404 Not Found", "text/plain", "")
                }
                val payload = body.toByteArray(StandardCharsets.UTF_8)
                val header = ("HTTP/1.1 $status\r\nContent-Type: $contentType\r\n" +
                    "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.UTF_8)
                connection.getOutputStream().use { output ->
                    output.write(header)
                    output.write(payload)
                    output.flush()
                }
            }
        }

        private fun AssetManager.read(path: String): String = open(path).use { stream ->
            String(stream.readBytes(), StandardCharsets.UTF_8)
        }

        private fun substitute(template: String, values: Map<String, String>): String {
            var result = template
            for ((key, value) in values) result = result.replace("{{$key}}", value)
            require("{{" !in result) { "unsubstituted fixture token" }
            return result
        }

        private fun xmlTime(epochSeconds: Long): String {
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(epochSeconds * 1_000L))
        }

        private companion object {
            const val HALF_HOUR_SECONDS = 1_800L
            const val ACCEPT_TIMEOUT_MILLIS = 1_000
            const val READ_TIMEOUT_MILLIS = 2_000
            const val JOIN_TIMEOUT_MILLIS = 2_000L
            const val MAX_REQUESTS = 64
        }
    }

    private companion object {
        const val TARGET_ACTIVITY = "com.devtunasoft.tuneros.MainActivity"
        const val REPORT_NAME = "mv03-ui-report.txt"
        const val REQUIRED_CHECKS = 6
        const val MAX_REPORT_BYTES = 8_192
        val CHANNELS = listOf("QA MV03 One", "QA MV03 Two", "QA MV03 Three")
    }
}

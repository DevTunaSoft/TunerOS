package com.devtunasoft.tuneros

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

/**
 * QA-01: compiled, deterministic TV-guide layout and remote-key acceptance.
 *
 * This is a platform-Instrumentation runner, so it adds no test dependency and touches no
 * application source. It launches the real MainActivity twice with synthetic loopback URLs:
 * once with an unavailable guide (source rows visible, no populated guide rows) and once with
 * a complete guide (source rows hidden, populated rows). It then reads the compiled view tree
 * for objective bounds, injects D-pad/OK keys through the instrumentation, and writes one small
 * PASS/FAIL report to app-private files. No screenshot, provider network, credential, real
 * media, human operation or guess about codec/first-frame behavior is involved.
 */
class TvGuideLayoutInstrumentation : Instrumentation() {

    private data class Outcome(val name: String, val passed: Boolean, val detail: String)

    private data class RowInfo(
        val channel: String,
        val row: Rect,
        val cell: Rect,
        val timeline: Rect,
        val description: String,
    )

    private data class GuideState(
        val sourceVisible: Boolean,
        val listHeight: Int,
        val listRect: Rect,
        val screenRect: Rect,
        val density: Float,
        val rowCount: Int,
        val rows: List<RowInfo>,
    )

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // The platform hidden run() hook is not reliably dispatched on every API level, so the
        // checks run on an explicitly started bounded thread. finish() ends the instrumentation.
        Thread({ runChecks() }, "qa-tv-layout-runner").apply { isDaemon = false }.start()
    }

    private fun runChecks() {
        val outcomes = ArrayList<Outcome>()
        var fixture: LoopbackFixture? = null
        var unloaded: Activity? = null
        var loaded: Activity? = null
        try {
            fixture = LoopbackFixture(context.assets, windowAnchor())
            val loopback = fixture ?: throw AssertionError("loopback fixture is missing")
            unloaded = launch(loopback.playlistUrl, loopback.missingGuideUrl)
            val unloadedState = waitForState(unloaded, expectLoaded = false)
            finishActivity(unloaded)
            unloaded = null

            loaded = launch(loopback.playlistUrl, loopback.guideUrl)
            val active = loaded ?: throw AssertionError("guide launch returned no activity")
            waitForImportReport()
            val loadedState = waitForState(active, expectLoaded = true)

            outcomes += execute("guide-loaded-vs-unloaded") {
                checkLoadedVsUnloaded(unloadedState, loadedState)
            }
            outcomes += execute("guide-row-bounds-no-clipping") {
                checkRowBounds(loadedState)
            }
            outcomes += execute("dpad-focus-transitions") {
                checkDpadFocus(active)
            }
            outcomes += execute("ok-select-to-play") {
                checkOkSelectToPlay(active, loopback)
            }
        } catch (error: Throwable) {
            outcomes += Outcome("qa01-runner", false, error.message ?: error.javaClass.simpleName)
        } finally {
            unloaded?.let { finishActivity(it) }
            loaded?.let { finishActivity(it) }
            fixture?.close()
        }

        val reportFile = File(targetContext.filesDir, REPORT_NAME)
        val failures = outcomes.count { !it.passed }
        val detail = buildString {
            append("QA-01 TV guide layout acceptance | device=").append(Build.MODEL)
            append(" | checks=").append(outcomes.size).append(" | failures=").append(failures).append('\n')
            for (outcome in outcomes) {
                append(if (outcome.passed) "PASS\t" else "FAIL\t")
                append(outcome.name).append('\t').append(outcome.detail).append('\n')
            }
            append("SUMMARY\t").append(outcomes.size - failures).append(" PASS\t").append(failures).append(" FAIL\n")
        }
        reportFile.writeText(detail)
        val result = Bundle().apply {
            putInt("qa_tv_checks", outcomes.size)
            putInt("qa_tv_failures", failures)
        }
        finish(if (failures == 0 && outcomes.size == REQUIRED_CHECKS) Activity.RESULT_OK else 1, result)
    }

    private fun execute(name: String, body: () -> String): Outcome = try {
        Outcome(name, true, body())
    } catch (error: Throwable) {
        Outcome(name, false, error.message ?: error.javaClass.simpleName)
    }

    private fun launch(playlistUrl: String, guideUrl: String): Activity {
        val intent = Intent().setClassName(targetContext, TARGET_ACTIVITY)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra("tuneros.debug.import_playlist_url", playlistUrl)
        intent.putExtra("tuneros.debug.import_guide_url", guideUrl)
        val activity = startActivitySync(intent)
        activeActivity = activity
        return activity
    }

    private fun finishActivity(activity: Activity) {
        onUi { activity.finish() }
        waitForIdleSync()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val finished = onUi { activity.isFinishing || activity.isDestroyed }
            if (finished) return
            SystemClock.sleep(50)
        }
    }

    private fun waitForImportReport() {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val file = File(targetContext.filesDir, "tuneros-import-report.txt")
            val text = if (file.isFile) {
                val bytes = ByteArray(8_192)
                val length = file.inputStream().use { it.read(bytes) }
                if (length > 0) String(bytes, 0, length, StandardCharsets.UTF_8) else ""
            } else {
                ""
            }
            if (text.contains("PASS\tqa-loopback-import")) return
            if (text.contains("FAIL\tqa-loopback-import")) {
                throw AssertionError("synthetic guide import failed in the application")
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("synthetic guide import report timed out")
    }

    private fun checkLoadedVsUnloaded(unloaded: GuideState, loaded: GuideState): String {
        require(unloaded.rowCount == EXPECTED_CHANNELS) {
            "playlist-only state has ${unloaded.rowCount} rows, expected $EXPECTED_CHANNELS"
        }
        require(unloaded.sourceVisible) { "source rows are hidden before the guide loads" }
        require(!loaded.sourceVisible) { "source rows stay visible after the guide loads" }
        require(loaded.rowCount == EXPECTED_CHANNELS) {
            "loaded state has ${loaded.rowCount} rows, expected $EXPECTED_CHANNELS"
        }
        require(loaded.rows.any { it.description.contains("Now on") }) {
            "loaded rows have no programme in the current window"
        }
        require(unloaded.listHeight > 0 && loaded.listHeight > 0) { "guide list has no height" }
        require(loaded.listHeight >= unloaded.listHeight) {
            "guide list shrank when the guide loaded: ${unloaded.listHeight}px -> ${loaded.listHeight}px"
        }
        return "playlist-only rows=${unloaded.rowCount} source=visible list=${unloaded.listHeight}px; " +
            "guide-loaded rows=${loaded.rowCount} source=hidden list=${loaded.listHeight}px"
    }

    private fun checkRowBounds(state: GuideState): String {
        require(state.rows.isNotEmpty()) { "no compiled guide row was found" }
        var fullyInside = 0
        var rowHeightDp = 0f
        for (row in state.rows) {
            require(row.row.left >= state.listRect.left && row.row.right <= state.listRect.right) {
                "row \"${row.channel}\" is outside the guide list horizontally"
            }
            require(row.row.left >= state.screenRect.left && row.row.right <= state.screenRect.right) {
                "row \"${row.channel}\" is outside the screen horizontally"
            }
            require(row.cell.width() > 0 && row.cell.height() > 0 &&
                row.timeline.width() > 0 && row.timeline.height() > 0) {
                "row \"${row.channel}\" has a zero-size channel cell or timeline"
            }
            require(row.cell.left >= row.row.left && row.cell.right <= row.row.right &&
                row.timeline.left >= row.row.left && row.timeline.right <= row.row.right) {
                "row \"${row.channel}\" children exceed the row"
            }
            rowHeightDp = row.row.height() / state.density
            require(rowHeightDp in 48f..96f) { "row height $rowHeightDp dp is outside the guide row band" }
            if (row.row.top >= state.listRect.top && row.row.bottom <= state.listRect.bottom &&
                row.row.bottom <= state.screenRect.bottom
            ) {
                fullyInside++
            }
        }
        require(fullyInside >= 1) { "no guide row is wholly inside the compiled guide list and screen" }
        return "visibleRows=${state.rows.size} fullyInside=$fullyInside rowHeight=${rowHeightDp.toInt()}dp " +
            "list=${state.listRect.width()}x${state.listRect.height()}px " +
            "screen=${state.screenRect.width()}x${state.screenRect.height()}px"
    }

    private fun checkDpadFocus(activity: Activity): String {
        focusCell(activity, "QA Layout One")
        val firstCell = focusDescription(activity)
        require(firstCell == "cell:QA Layout One") { "initial focus is $firstCell" }

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await(5_000L, "DOWN from the first row") { focusDescription(activity) == "cell:QA Layout Two" }
        press(KeyEvent.KEYCODE_DPAD_UP)
        await(5_000L, "UP from the second row") { focusDescription(activity) == "cell:QA Layout One" }

        focusTimeline(activity, "QA Layout One")
        val now = focusDescription(activity)
        require(now.startsWith("timeline:") && now.contains("Now on")) {
            "timeline focus did not select the current programme: $now"
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        await(5_000L, "RIGHT to the next programme") {
            val value = focusDescription(activity)
            value.startsWith("timeline:") && value.contains("Next on")
        }
        val next = focusDescription(activity)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        await(5_000L, "LEFT back to the current programme") { focusDescription(activity) == now }
        return "DOWN/UP: QA Layout One -> QA Layout Two -> QA Layout One; " +
            "RIGHT/LEFT: ${now.removePrefix("timeline:")} -> ${next.removePrefix("timeline:")} -> current"
    }

    private fun checkOkSelectToPlay(activity: Activity, fixture: LoopbackFixture): String {
        focusCell(activity, "QA Layout One")
        val firstPath = "/stream/qa.layout.one"
        val beforeCell = fixture.count(firstPath)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await(8_000L, "OK on the channel cell") { fixture.count(firstPath) > beforeCell }
        val cellStatus = statusAfter(activity, "QA Layout One")

        focusTimeline(activity, "QA Layout Two")
        val secondPath = "/stream/qa.layout.two"
        val beforeTimeline = fixture.count(secondPath)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await(8_000L, "OK on the programme timeline") { fixture.count(secondPath) > beforeTimeline }
        val timelineStatus = statusAfter(activity, "QA Layout Two")
        return "OK on channel cell requested $firstPath ${fixture.count(firstPath) - beforeCell} time(s) status=$cellStatus; " +
            "OK on programme timeline requested $secondPath ${fixture.count(secondPath) - beforeTimeline} time(s) status=$timelineStatus; " +
            "playback request only, no decode claim"
    }

    /** The app status line may move on quickly when the synthetic stream answers 404; report, do not assert it. */
    private fun statusAfter(activity: Activity, channel: String): String {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val value = onUi { visibleStatus(activity) }
            if (value.startsWith("Connecting: $channel") || value.startsWith("Playing: $channel")) return value
            SystemClock.sleep(50)
        }
        return onUi { visibleStatus(activity) }.ifEmpty { "not observed" }
    }

    private fun focusCell(activity: Activity, channel: String) {
        val found = onUi {
            val cell = guideCells(activity).firstOrNull { it.text?.toString() == channel }
                ?: return@onUi false
            cell.isFocusableInTouchMode = true
            cell.requestFocusFromTouch()
        }
        require(found) {
            "channel cell $channel is not on screen; cells=" + onUi {
                guideCells(activity).mapNotNull { it.text?.toString() }
            } + " focus=" + focusDescription(activity)
        }
        await(3_000L, "focus on $channel") { focusDescription(activity) == "cell:$channel" }
    }

    private fun focusTimeline(activity: Activity, channel: String) {
        val found = onUi {
            val cell = guideCells(activity).firstOrNull { it.text?.toString() == channel }
                ?: return@onUi false
            val row = cell.parent as? ViewGroup ?: return@onUi false
            val timeline = (0 until row.childCount).map { row.getChildAt(it) }
                .firstOrNull { it is ProgramTimelineView } ?: return@onUi false
            timeline.isFocusableInTouchMode = true
            timeline.requestFocusFromTouch()
        }
        require(found) {
            "timeline of $channel is not on screen; cells=" + onUi {
                guideCells(activity).mapNotNull { it.text?.toString() }
            } + " focus=" + focusDescription(activity)
        }
        await(3_000L, "timeline focus on $channel") { focusDescription(activity).startsWith("timeline:") }
    }

    private fun focusDescription(activity: Activity): String = onUi {
        val focused = activity.currentFocus ?: return@onUi "none"
        var row: View? = focused
        while (row != null && row.parent !is GuideRowList) row = row.parent as? View
        if (row == null) return@onUi "outside:" + focused.javaClass.simpleName
        val cell = (row as? ViewGroup)?.let { group ->
            (0 until group.childCount).map { group.getChildAt(it) }
                .filterIsInstance<TextView>().firstOrNull()?.text?.toString()
        } ?: ""
        when (focused) {
            is ProgramTimelineView -> "timeline:" + (focused.contentDescription?.toString() ?: "")
            else -> "cell:$cell"
        }
    }

    private fun visibleStatus(activity: Activity): String =
        findViews(activity.window.decorView).filterIsInstance<TextView>()
            .mapNotNull { it.text?.toString() }
            .firstOrNull { it.startsWith("Connecting: ") || it.startsWith("Playing: ") }
            .orEmpty()

    private fun waitForState(activity: Activity, expectLoaded: Boolean): GuideState {
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        var last: GuideState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = onUi { collect(activity) }
            last = state
            val populated = state.rowCount == EXPECTED_CHANNELS && state.rows.any { it.description.isNotEmpty() }
            if (populated && (if (expectLoaded) !state.sourceVisible else state.sourceVisible) &&
                (!expectLoaded || state.rows.any { it.description.contains("Now on") })
            ) {
                return state
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("guide state (loaded=$expectLoaded) never appeared; rows=${last?.rowCount} " +
            "sourceVisible=${last?.sourceVisible}")
    }

    private fun collect(activity: Activity): GuideState {
        val views = findViews(activity.window.decorView)
        val list = views.filterIsInstance<GuideRowList>().firstOrNull() as? ListView
        val sourcePanel = views.filterIsInstance<EditText>()
            .firstOrNull { it.hint?.toString() == "M3U playlist URL" }?.parent?.parent as? View
        val rows = ArrayList<RowInfo>()
        if (list != null) {
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index) as? ViewGroup ?: continue
                val cell = (0 until row.childCount).map { row.getChildAt(it) }
                    .filterIsInstance<TextView>().firstOrNull() ?: continue
                val timeline = (0 until row.childCount).map { row.getChildAt(it) }
                    .filterIsInstance<ProgramTimelineView>().firstOrNull() ?: continue
                rows += RowInfo(
                    channel = cell.text?.toString().orEmpty(),
                    row = bounds(row),
                    cell = bounds(cell),
                    timeline = bounds(timeline),
                    description = timeline.contentDescription?.toString().orEmpty(),
                )
            }
        }
        val metrics = activity.resources.displayMetrics
        return GuideState(
            sourceVisible = sourcePanel?.visibility == View.VISIBLE,
            listHeight = list?.height ?: 0,
            listRect = list?.let { bounds(it) } ?: Rect(),
            screenRect = Rect(0, 0, metrics.widthPixels, metrics.heightPixels),
            density = metrics.density,
            rowCount = list?.adapter?.count ?: 0,
            rows = rows,
        )
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /** UI thread only: channel cells of the guide rows; the status label may repeat a channel name. */
    private fun guideCells(activity: Activity): List<TextView> {
        val list = findViews(activity.window.decorView).filterIsInstance<GuideRowList>().firstOrNull()
            ?: return emptyList()
        val cells = ArrayList<TextView>()
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) as? ViewGroup ?: continue
            for (child in 0 until row.childCount) {
                (row.getChildAt(child) as? TextView)?.let { cells += it }
            }
        }
        return cells
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
            SystemClock.sleep(50)
        }
        throw AssertionError("$label timed out; focus=${focusDescriptionSafe()}")
    }

    private fun focusDescriptionSafe(): String {
        val activity = activeActivity ?: return "none"
        return try {
            focusDescription(activity)
        } catch (_: Throwable) {
            "unavailable"
        }
    }

    @Volatile
    private var activeActivity: Activity? = null

    private fun press(keyCode: Int) {
        sendKeyDownUpSync(keyCode)
        waitForIdleSync()
    }

    private fun windowAnchor(): Long {
        val seconds = System.currentTimeMillis() / 1_000L
        return seconds - seconds % HALF_HOUR_SECONDS
    }

    /** Bounded 127.0.0.1 fixture server. It answers only four known paths and at most 64 requests. */
    private class LoopbackFixture(assets: AssetManager, anchor: Long) {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        private val requestedPaths = Collections.synchronizedList(ArrayList<String>())
        @Volatile
        private var running = true
        private val thread: Thread

        val playlistUrl: String get() = "http://127.0.0.1:$port/playlist.m3u"
        val guideUrl: String get() = "http://127.0.0.1:$port/guide.xml"
        val missingGuideUrl: String get() = "http://127.0.0.1:$port/missing-guide.xml"

        private val playlist: String
        private val guide: String

        init {
            server.soTimeout = ACCEPT_TIMEOUT_MILLIS
            val values = mapOf(
                "PORT" to port.toString(),
                "T_MINUS" to xmlTime(anchor - HALF_HOUR_SECONDS),
                "T_NOW" to xmlTime(anchor),
                "T_PLUS" to xmlTime(anchor + HALF_HOUR_SECONDS),
                "T_LATER" to xmlTime(anchor + 3 * HALF_HOUR_SECONDS),
            )
            playlist = substitute(assets.read("tv-guide-layout/playlist.m3u.tmpl"), values)
            guide = substitute(assets.read("tv-guide-layout/guide.xml.tmpl"), values)
            thread = Thread({ serveLoop() }, "qa-tv-layout-fixture").apply {
                isDaemon = true
                start()
            }
        }

        fun count(path: String): Int = synchronized(requestedPaths) { requestedPaths.count { it == path } }

        fun close() {
            running = false
            runCatching { server.close() }
            thread.join(JOIN_TIMEOUT_MILLIS)
        }

        private fun serveLoop() {
            while (running) {
                try {
                    val socket = server.accept()
                    serve(socket)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    if (!running) return
                    return
                }
            }
        }

        private fun serve(socket: Socket) {
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
        const val REPORT_NAME = "qa-tv-layout-report.txt"
        const val EXPECTED_CHANNELS = 3
        const val REQUIRED_CHECKS = 4
        const val HALF_HOUR_SECONDS = 1_800L
    }
}

package com.devtunasoft.tuneros

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot

/** What the guide focus checks need from the activity: the guide's own list and adapter. Every call runs on the UI thread. */
internal interface GuideFocusDriver {
    /** The guide's channel list. The rows are found from here. */
    fun guideList(): ListView

    /** Shows [rows] through the guide's own adapter, with the programmes of [snapshot] between the two times. */
    fun showGuideRows(
        rows: List<Channel>,
        snapshot: GuideSnapshot,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
        nowEpochSeconds: Long,
    )

    /** The start of the guide window that the app shows. */
    fun windowStartEpochSeconds(): Long

    /** Puts the app's own channel list back, showing the window that starts at [windowStartEpochSeconds]. */
    fun restoreChannelList(windowStartEpochSeconds: Long)

    /** How many window shifts the guide has asked for. The app counts each request. */
    fun windowShiftCount(): Int

    /** The direction of the latest window shift request: -1 for the left edge, 1 for the right edge. */
    fun lastWindowShiftDirection(): Int
}

/**
 * Debug builds only. Shows guide rows, each with three programmes, through the guide's own adapter, and checks the focus that a
 * TV remote depends on. (a) The channel cell takes focus. (b) RIGHT moves to the next programme and LEFT returns to the first,
 * with no window shift. (c) LEFT on the first programme asks the guide to shift its window once, by -1. (d) UP and DOWN move the
 * focus to the neighbouring row, and DOWN from the last row on screen shows the next row and gives it the focus. Keys go to the
 * focused view first, as the remote sends them. Window shifts are counted by the app, not read from the screen. Each check uses
 * its own channel ids, because the adapter remembers the programme that had focus on a channel id. The app's own channel list
 * is put back at the end.
 */
internal class GuideFocusChecks(
    private val activity: Activity,
    private val screen: ScreenDriver,
    private val guide: GuideFocusDriver,
    private val report: (check: String, passed: Boolean, detail: String) -> Unit,
) {
    private val nowEpochSeconds = System.currentTimeMillis() / 1000L
    private val fromEpochSeconds = nowEpochSeconds - nowEpochSeconds % STEP_SECONDS
    private val toEpochSeconds = fromEpochSeconds + WINDOW_SECONDS
    private val snapshot = snapshotFrom(fromEpochSeconds)

    fun run() {
        // The guide builds its per-channel index on first use. Build it here, off the UI thread, as an import does.
        snapshot.prepareIndex()
        var savedStart = 0L
        screen.onUi { savedStart = guide.windowStartEpochSeconds() }
        try {
            runCheck(CELL_CHECK) { channelCellTakesFocus() }
            runCheck(PROGRAMME_CHECK) { rightAndLeftMoveProgramme() }
            runCheck(EDGE_CHECK) { leftOnFirstProgrammeShiftsWindow() }
            runCheck(ROWS_CHECK) { upAndDownLeaveTimeline() }
        } finally {
            screen.onUi { guide.restoreChannelList(savedStart) }
        }
    }

    /** Runs one check and reports it under its own name, so that an exception ends only that check. */
    private fun runCheck(name: String, body: () -> Pair<Boolean, String>) {
        val outcome = try {
            body()
        } catch (error: Exception) {
            Pair(false, "the check stopped on " + error.javaClass.simpleName)
        }
        report(name, outcome.first, outcome.second)
    }

    /** (a) A focus request on the channel cell of a row gives the focus to that cell. */
    private fun channelCellTakesFocus(): Pair<Boolean, String> {
        showRows(TAG_CELL, PAIR)
        if (!rowsOnScreen()) return Pair(false, "the first guide row is not on screen; ${geometryNow()}")
        var focused = false
        var focusText = ""
        screen.onUi {
            val cell = channelCell(ALPHA) ?: return@onUi
            focusByTouch(listOf(cell)) { cell.requestFocus() }
            focused = activity.currentFocus === cell
            focusText = describeFocus()
        }
        val detail = if (focused) "the channel cell \"$ALPHA\" takes focus" else "focus is on $focusText, not the channel cell"
        return Pair(focused, detail)
    }

    /** (b) RIGHT moves the focus to the next programme cell, LEFT returns it, and neither key shifts the window. */
    private fun rightAndLeftMoveProgramme(): Pair<Boolean, String> {
        showRows(TAG_PROGRAMMES, PAIR)
        if (!rowsOnScreen()) return Pair(false, "the first guide row is not on screen; ${geometryNow()}")
        var focused = false
        var focusText = ""
        var first = ""
        var afterRight = ""
        var afterLeft = ""
        var stillFocused = false
        var shifts = 0
        screen.onUi {
            val timeline = timelineOf(ALPHA) ?: return@onUi
            focusByTouch(rowViews()) { timeline.requestFocus() }
            focused = activity.currentFocus === timeline
            focusText = describeFocus()
            if (!focused) return@onUi
            first = programmeLabel(timeline)
            val before = guide.windowShiftCount()
            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            afterRight = programmeLabel(timeline)
            sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            afterLeft = programmeLabel(timeline)
            stillFocused = activity.currentFocus === timeline
            shifts = guide.windowShiftCount() - before
        }
        if (!focused) return Pair(false, "the programme timeline did not take focus; focus is on $focusText")
        val passed = first == FIRST_LABEL && afterRight == SECOND_LABEL && afterLeft == FIRST_LABEL &&
            stillFocused && shifts == 0
        val detail = "from \"$first\", RIGHT gives \"$afterRight\" and LEFT gives \"$afterLeft\"; " +
            "window shifts: $shifts; focus stays on the timeline: $stillFocused"
        return Pair(passed, detail)
    }

    /** (c) LEFT on the first programme asks the guide to shift its window once, by -1. The app counts the request. */
    private fun leftOnFirstProgrammeShiftsWindow(): Pair<Boolean, String> {
        showRows(TAG_EDGE, PAIR)
        if (!rowsOnScreen()) return Pair(false, "the first guide row is not on screen; ${geometryNow()}")
        var focused = false
        var focusText = ""
        var first = ""
        var requests = 0
        var direction = 0
        screen.onUi {
            val timeline = timelineOf(ALPHA) ?: return@onUi
            focusByTouch(rowViews()) { timeline.requestFocus() }
            focused = activity.currentFocus === timeline
            focusText = describeFocus()
            if (!focused) return@onUi
            first = programmeLabel(timeline)
            val before = guide.windowShiftCount()
            sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            requests = guide.windowShiftCount() - before
            direction = guide.lastWindowShiftDirection()
        }
        if (!focused) return Pair(false, "the programme timeline did not take focus; focus is on $focusText")
        val passed = first == FIRST_LABEL && requests == 1 && direction == -1
        return Pair(passed, "LEFT on \"$first\" made $requests window shift request(s), direction $direction")
    }

    /**
     * (d) UP and DOWN move the focus to the neighbouring row, whether that row is on screen or the list has to scroll to it.
     * DOWN from the first row reaches the second, UP from the second returns to the first, and DOWN from the last row on screen
     * shows the next row and gives it the focus. A short guide list shows only one row, so the scroll path carries the steps.
     */
    private fun upAndDownLeaveTimeline(): Pair<Boolean, String> {
        showRows(TAG_ROWS, NAMES)
        if (!rowsOnScreen()) return Pair(false, "the first guide row is not on screen; ${geometryNow()}")
        val down = stepTo(ALPHA, BRAVO, KeyEvent.KEYCODE_DPAD_DOWN, View.FOCUS_DOWN)
        val up = stepTo(BRAVO, ALPHA, KeyEvent.KEYCODE_DPAD_UP, View.FOCUS_UP)
        val past = downPastLastRowFollowsScroll()
        val passed = down.first && up.first && past.first
        return Pair(passed, "DOWN from \"$ALPHA\": ${down.second}; UP from \"$BRAVO\": ${up.second}; ${past.second}; ${geometryNow()}")
    }

    /**
     * One step from the row of [source] to the row of [expected]. The timeline of [source] must pass the key on. Then the key
     * goes to the focused view, and the window reports whether a view consumed it. A consumed key has to move the focus to the
     * expected row itself, although the focus may arrive a moment later. A key that no view consumed is one the framework moves
     * focus for, with a focus search, so the check runs that search. Otherwise the step fails.
     */
    private fun stepTo(source: String, expected: String, keyCode: Int, direction: Int): Pair<Boolean, String> {
        var sourceView: View? = null
        var focused = false
        var focusText = ""
        var passedOn = false
        var consumed = false
        var neighbourShown = false
        screen.onUi {
            val timeline = timelineOf(source) ?: return@onUi
            sourceView = timeline
            focusByTouch(rowViews()) {
                timeline.requestFocus()
                focused = activity.currentFocus === timeline
                focusText = describeFocus()
                if (!focused) return@focusByTouch
                neighbourShown = channelCell(expected) != null
                passedOn = !timeline.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                consumed = sendKey(keyCode)
            }
        }
        if (!focused) return Pair(false, "focus did not reach \"$source\"; focus is on $focusText")
        // A key that a view consumed may take the focus a moment later, once the list has shown the expected row.
        if (consumed) screen.waitForScreen(FOCUS_TIMEOUT_MILLIS) { focusedOnRow(expected) }
        var landed = false
        var landedOn = ""
        screen.onUi {
            if (!consumed && activity.currentFocus === sourceView) {
                focusByTouch(rowViews()) { sourceView?.focusSearch(direction)?.requestFocus() }
            }
            landed = focusedOnRow(expected)
            landedOn = describeFocus()
        }
        val path = if (neighbourShown) "the neighbour was on screen" else "the list scrolled to the neighbour"
        val detail = when {
            !passedOn -> "the timeline of \"$source\" consumed the key itself"
            landed && consumed -> "the key moves focus from \"$source\" to $landedOn; $path"
            landed -> "no view took the key, so the focus search moves focus from \"$source\" to $landedOn; $path"
            consumed -> "a view consumed the key, and focus is on $landedOn, not \"$expected\""
            else -> "no view took the key, and the focus search reached $landedOn, not \"$expected\""
        }
        return Pair(passedOn && landed, detail)
    }

    /** DOWN from the last row on screen shows the next row, and that row takes the focus once the list has laid it out. */
    private fun downPastLastRowFollowsScroll(): Pair<Boolean, String> {
        var focused = false
        var focusText = ""
        var lastName = ""
        var nextName = ""
        var consumed = false
        screen.onUi {
            val list = guide.guideList()
            val last = list.lastVisiblePosition
            val row = list.getChildAt(last - list.firstVisiblePosition) ?: return@onUi
            val timeline = timelineIn(row) ?: return@onUi
            lastName = cellIn(row)?.text?.toString() ?: ""
            nextName = NAMES.getOrElse(last + 1) { "" }
            focusByTouch(listOf(timeline)) { timeline.requestFocus() }
            focused = activity.currentFocus === timeline
            focusText = describeFocus()
            if (focused) consumed = sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        if (!focused) return Pair(false, "focus did not reach the last row on screen; focus is on $focusText")
        if (nextName.isEmpty()) return Pair(false, "every row is on screen, so there is no next row to scroll to")
        val followed = screen.waitForScreen(FOCUS_TIMEOUT_MILLIS) { focusedOnRow(nextName) }
        val detail = if (followed) {
            "DOWN past the last row on screen shows \"$nextName\" and gives it the focus; key consumed: $consumed"
        } else {
            "DOWN from \"$lastName\" did not give the focus to \"$nextName\"; key consumed: $consumed"
        }
        return Pair(followed, detail)
    }

    /** Shows [names] as the rows of one check, through the guide's own adapter. [tag] keeps this check's channel ids apart. */
    private fun showRows(tag: String, names: List<String>) {
        val rows = names.map { channelFor(tag, it) }
        screen.onUi { guide.showGuideRows(rows, snapshot, fromEpochSeconds, toEpochSeconds, nowEpochSeconds) }
    }

    /**
     * Waits until the first row is on screen and laid out after its bind. A pending layout could leave a stale row in use. The
     * other rows may sit below the list's edge: a short guide list shows one row, and the steps then reach the next row by
     * scrolling to it.
     */
    private fun rowsOnScreen(): Boolean = screen.waitForScreen(ROWS_TIMEOUT_MILLIS) {
        !guide.guideList().isLayoutRequested && rowReady(ALPHA)
    }

    /** Runs on the UI thread: the guide list's height and the rows laid out in it, for the report. */
    private fun listGeometry(): String {
        val list = guide.guideList()
        val density = activity.resources.displayMetrics.density
        return "guide list ${list.height} px (${list.height / density} dp), rows laid out ${list.childCount}, " +
            "positions ${list.firstVisiblePosition} to ${list.lastVisiblePosition}"
    }

    /** The guide list's geometry, read on the UI thread and returned to the test thread. */
    private fun geometryNow(): String {
        var text = ""
        screen.onUi { text = listGeometry() }
        return text
    }

    /**
     * The emulator starts in touch mode. There requestFocus() ignores a view that is not focusable by touch, and the focus
     * search that the framework runs for an unhandled direction key offers only such views. So the views are made focusable
     * by touch while the request and the search run, and each flag is put back at once. runChannelRowRingCheck makes its
     * request the same way, and its focus survives the restore.
     */
    private fun <T> focusByTouch(views: List<View>, request: () -> T): T {
        val saved = views.map { it.isFocusableInTouchMode }
        views.forEach { it.isFocusableInTouchMode = true }
        try {
            return request()
        } finally {
            views.forEachIndexed { index, view -> view.isFocusableInTouchMode = saved[index] }
        }
    }

    /** A key press as the remote sends it: down, then up, both through the window. Returns whether a view consumed the down. */
    private fun sendKey(keyCode: Int): Boolean {
        val decor = activity.window.decorView
        val consumed = decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return consumed
    }

    /** Runs on the UI thread: whether the row of [name] is on screen and laid out. */
    private fun rowReady(name: String): Boolean {
        val cell = channelCell(name) ?: return false
        val timeline = timelineOf(name) ?: return false
        return cell.isShown && timeline.isShown && timeline.width > 0
    }

    /** Runs on the UI thread: whether the focus is on a view of the row named [name]. */
    private fun focusedOnRow(name: String): Boolean {
        val focus = activity.currentFocus ?: return false
        return rowCellOf(focus)?.text?.toString() == name
    }

    /** Runs on the UI thread: the channel cell that shows [name], or null while it is not on screen. */
    private fun channelCell(name: String): TextView? = findCell(guide.guideList(), name)

    private fun findCell(view: View, name: String): TextView? {
        if (view is TextView && view.isFocusable && view.text?.toString() == name) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findCell(view.getChildAt(index), name)?.let { return it }
            }
        }
        return null
    }

    /** Runs on the UI thread: the programme timeline in the row of [name], or null while that row is not on screen. */
    private fun timelineOf(name: String): ProgramTimelineView? {
        val row = channelCell(name)?.parent as? View ?: return null
        return timelineIn(row)
    }

    /** Runs on the UI thread: the channel cell in [row], or null. */
    private fun cellIn(row: View): TextView? {
        val group = row as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? TextView)?.let { return it }
        }
        return null
    }

    /** Runs on the UI thread: the programme timeline in [row], or null. */
    private fun timelineIn(row: View): ProgramTimelineView? {
        val group = row as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? ProgramTimelineView)?.let { return it }
        }
        return null
    }

    /** Runs on the UI thread: the cells and timelines of the first two rows, the views a focus request or search can reach. */
    private fun rowViews(): List<View> = listOfNotNull<View>(
        channelCell(ALPHA), timelineOf(ALPHA), channelCell(BRAVO), timelineOf(BRAVO),
    )

    /** Runs on the UI thread: the channel cell in the row of [view], or null. */
    private fun rowCellOf(view: View): TextView? {
        val row = view.parent as? View ?: return null
        return cellIn(row)
    }

    /** Runs on the UI thread: what a view is, by its row, for the report. */
    private fun describeView(view: View): String {
        val cell = if (view is TextView) view else rowCellOf(view)
        val name = cell?.text?.toString() ?: ""
        return when (view) {
            is ProgramTimelineView -> "the programme timeline of \"$name\""
            is TextView -> "the channel cell \"$name\""
            else -> view.javaClass.simpleName
        }
    }

    /** Runs on the UI thread: what has the focus, for the report. */
    private fun describeFocus(): String = activity.currentFocus?.let { describeView(it) } ?: "nothing"

    /** Runs on the UI thread: the channel and programme the timeline has selected, which is its accessible label. */
    private fun programmeLabel(timeline: ProgramTimelineView): String = timeline.contentDescription?.toString() ?: ""

    private fun channelFor(tag: String, name: String): Channel {
        val id = guideId(tag, name)
        return Channel(id = id, name = name, streamUrl = "asset:///ui-test.mp4", guideId = id, group = "News")
    }

    private fun guideId(tag: String, name: String): String = "ui-check-" + tag + "-" + name.lowercase().replace(' ', '-')

    /** Three programmes per channel. They cover the window exactly, so a row has no gap for the focus to skip. */
    private fun snapshotFrom(from: Long): GuideSnapshot {
        val programs = ArrayList<GuideProgram>()
        for (tag in TAGS) {
            for (name in NAMES) {
                TITLES.forEachIndexed { index, title ->
                    val start = from + index * PROGRAMME_SECONDS
                    programs += GuideProgram(
                        channelId = guideId(tag, name),
                        title = title,
                        startEpochSeconds = start,
                        endEpochSeconds = start + PROGRAMME_SECONDS,
                    )
                }
            }
        }
        return GuideSnapshot(programs = programs, channelNames = emptyMap())
    }

    private companion object {
        const val ALPHA = "Guide Focus Alpha"
        const val BRAVO = "Guide Focus Bravo"
        val PAIR = listOf(ALPHA, BRAVO)
        val NAMES = listOf(
            ALPHA, BRAVO, "Guide Focus Charlie", "Guide Focus Delta", "Guide Focus Echo", "Guide Focus Foxtrot",
            "Guide Focus Golf", "Guide Focus Hotel",
        )
        const val FIRST_TITLE = "First show"
        const val SECOND_TITLE = "Second show"
        val TITLES = listOf(FIRST_TITLE, SECOND_TITLE, "Third show")
        val FIRST_LABEL = "$ALPHA, $FIRST_TITLE"
        val SECOND_LABEL = "$ALPHA, $SECOND_TITLE"
        const val TAG_CELL = "cell"
        const val TAG_PROGRAMMES = "programmes"
        const val TAG_EDGE = "edge"
        const val TAG_ROWS = "rows"
        val TAGS = listOf(TAG_CELL, TAG_PROGRAMMES, TAG_EDGE, TAG_ROWS)
        const val STEP_SECONDS = 1_800L
        const val PROGRAMME_SECONDS = 1_200L
        const val WINDOW_SECONDS = 3_600L
        const val ROWS_TIMEOUT_MILLIS = 15_000L
        const val FOCUS_TIMEOUT_MILLIS = 5_000L
        const val CELL_CHECK = "guide-focus-channel-cell-takes-focus"
        const val PROGRAMME_CHECK = "guide-focus-right-left-moves-programme"
        const val EDGE_CHECK = "guide-focus-left-edge-shifts-window"
        const val ROWS_CHECK = "guide-focus-up-down-leaves-timeline"
    }
}

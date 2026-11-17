package com.devtunasoft.tuneros

import android.app.Activity
import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.view.ViewGroup
import kotlin.math.abs

/** What the focus checks need from the activity: access to the screen on the UI thread, and the dialog that is open. */
internal interface ScreenDriver {
    /** Runs [block] on the UI thread and waits for it. */
    fun onUi(block: () -> Unit)

    /** Runs [block] on the UI thread and returns what it produced. */
    fun onUiBool(block: () -> Boolean): Boolean

    /** Polls [condition] on the UI thread until it holds or the time runs out. */
    fun waitForScreen(timeoutMillis: Long, condition: () -> Boolean): Boolean

    /** The dialog the app most recently showed, or null. Read it on the UI thread. */
    fun activeDialog(): AlertDialog?

    /** The parental session flag. Safe to read and set from any thread. */
    fun parentalSessionUnlocked(): Boolean
    fun setParentalSessionUnlocked(unlocked: Boolean)
}

/**
 * Debug builds only. Checks the path a TV remote needs: the Parental PIN button can be reached with the D-pad alone, OK
 * presses it and opens its dialog, BACK closes that dialog, and focus goes back to the button. Keys go through each window's
 * own dispatch, as the system sends them. The PIN prompt is only cancelled, so nothing is saved, and the session flag is
 * restored afterwards. Each check reports one PASS or FAIL line through [report].
 */
internal class DeviceFocusChecks(
    private val activity: Activity,
    private val driver: ScreenDriver,
    private val report: (check: String, passed: Boolean, detail: String) -> Unit,
) {
    /** The view that had focus before the last press, and how many presses in a row left focus there. UI thread only. */
    private var lastFocus: View? = null
    private var stuckCount = 0

    fun run() {
        var reached = driver.onUiBool { focusedButtonText() == TARGET }
        var presses = 0
        while (!reached && presses < MAX_DPAD_PRESSES) {
            driver.onUi { pressTowardTarget() }
            Thread.sleep(PAUSE_MILLIS)
            presses++
            reached = driver.onUiBool { focusedButtonText() == TARGET }
        }
        var focusAt = ""
        if (!reached) driver.onUi { focusAt = describeFocus() }
        val detail = if (reached) {
            "the Parental PIN button is reached with the D-pad alone"
        } else {
            "the Parental PIN button is not reached; focus stopped on $focusAt"
        }
        report("focus-reach-parental-pin", reached, detail)
        if (!reached) return
        report("focus-ring-on-focused-button", driver.onUiBool { focusRingShown() }, "the focused button draws the focus ring")

        val savedUnlocked = driver.parentalSessionUnlocked()
        driver.setParentalSessionUnlocked(false)
        try {
            driver.onUi { sendKey(activity.window.decorView, KeyEvent.KEYCODE_DPAD_CENTER) }
            val opened = driver.waitForScreen(DIALOG_TIMEOUT_MILLIS) { pinDialogOpen() }
            report("focus-ok-opens-pin-dialog", opened, "OK on the focused button opens its PIN dialog")
            if (!opened) return

            driver.onUi { driver.activeDialog()?.window?.decorView?.let { sendKey(it, KeyEvent.KEYCODE_BACK) } }
            val closed = driver.waitForScreen(DIALOG_TIMEOUT_MILLIS) { driver.activeDialog()?.isShowing != true }
            val restored = closed && driver.waitForScreen(FOCUS_TIMEOUT_MILLIS) { focusedButtonText() == TARGET }
            report(
                "focus-returns-after-dialog",
                restored,
                "BACK closes the dialog and focus goes back to the Parental PIN button",
            )
        } finally {
            driver.setParentalSessionUnlocked(savedUnlocked)
        }
    }

    /** Runs on the UI thread: the text of the focused button, or null when the focus is not on a button. */
    private fun focusedButtonText(): String? = (activity.currentFocus as? Button)?.text?.toString()

    /** Runs on the UI thread: true while a PIN prompt with its confirm button is on screen. */
    private fun pinDialogOpen(): Boolean {
        val dialog = driver.activeDialog() ?: return false
        if (!dialog.isShowing) return false
        val label = dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text?.toString() ?: return false
        return label in PIN_DIALOG_CONFIRM_LABELS
    }

    /** Runs on the UI thread: one D-pad press toward the target. Focus that did not move last time turns to the other axis. */
    private fun pressTowardTarget() {
        val focused = activity.currentFocus
        stuckCount = if (focused != null && focused === lastFocus) stuckCount + 1 else 0
        lastFocus = focused
        sendKey(activity.window.decorView, directionTowardTarget(useSecondary = stuckCount % 2 == 1))
    }

    /** Runs on the UI thread: the D-pad key that moves focus toward the Parental PIN button. */
    private fun directionTowardTarget(useSecondary: Boolean): Int {
        val target = findButton(activity.window.decorView, TARGET) ?: return KeyEvent.KEYCODE_DPAD_DOWN
        val focused = activity.currentFocus ?: return KeyEvent.KEYCODE_DPAD_DOWN
        val (targetX, targetY) = centre(target)
        val (focusX, focusY) = centre(focused)
        val dx = targetX - focusX
        val dy = targetY - focusY
        val horizontal = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val vertical = if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        val horizontalFirst = abs(dx) >= abs(dy)
        return if (horizontalFirst != useSecondary) horizontal else vertical
    }

    private fun findButton(view: View, label: String): Button? {
        if (view is Button && view.text?.toString() == label) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButton(view.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

    private fun centre(view: View): Pair<Int, Int> {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Pair(location[0] + view.width / 2, location[1] + view.height / 2)
    }

    /** Runs on the UI thread: what has focus, for the report when the button is not reached. */
    private fun describeFocus(): String {
        val focused = activity.currentFocus ?: return "nothing"
        val text = (focused as? Button)?.text?.toString()
        val name = focused.javaClass.simpleName
        return if (text.isNullOrEmpty()) name else "$name \"$text\""
    }

    /** Runs on the UI thread: true when the focused view draws a focus state. */
    private fun focusRingShown(): Boolean {
        val focused = activity.currentFocus ?: return false
        return focused.foreground?.state?.contains(android.R.attr.state_focused) == true
    }

    /** A key press as the system sends it: down, then up. The focus moves on down and the button presses on up. */
    private fun sendKey(view: View, keyCode: Int) {
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private companion object {
        const val TARGET = "Parental PIN"
        val PIN_DIALOG_CONFIRM_LABELS = setOf("Save", "Unlock")
        const val MAX_DPAD_PRESSES = 60
        const val PAUSE_MILLIS = 200L
        const val DIALOG_TIMEOUT_MILLIS = 10_000L
        const val FOCUS_TIMEOUT_MILLIS = 5_000L
    }
}

package com.devtunasoft.tuneros

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ListView
import android.widget.TextView

/**
 * The cable guide's channel list. ListView takes UP and DOWN for its own selection, so the focus stayed on the programme or
 * channel cell it was on when the remote moved to another row. This list handles those two keys itself: the focus moves to the
 * same kind of view in the neighbouring row. A row that is off screen is shown first, and the focus follows once the list has
 * laid it out. When there is no neighbouring row, ListView keeps the key, as before. LEFT and RIGHT are not handled here, so the
 * programme moves and the window shift at the edges stay with the row's views.
 */
internal class GuideRowList(context: Context) : ListView(context) {
    private var pendingRowFocus: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (moveFocusToNeighbourRow(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    /** Returns true when the key moves the focus, or starts moving it to a row that is off screen. */
    private fun moveFocusToNeighbourRow(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> return false
        }
        val focused = findFocus() ?: return false
        val row = rowOf(focused) ?: return false
        val position = getPositionForView(row)
        val target = position + step
        val count = adapter?.count ?: 0
        if (position < 0 || target < 0 || target >= count) return false
        val shown = getChildAt(target - firstVisiblePosition)
        if (shown != null) return focusSameKind(focused, shown)

        // The neighbour is off screen. Show it at the edge the focus comes from, then focus it once the list has laid it out.
        // Scrolling detaches the focused row, and the list can then take the focus to itself after the request. So the request
        // is repeated on later layout passes until the focus has stayed on the neighbour after a request.
        pendingRowFocus?.let { viewTreeObserver.removeOnGlobalLayoutListener(it) }
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            private var passes = 0
            private var requested = false

            override fun onGlobalLayout() {
                passes++
                val laidOut = getChildAt(target - firstVisiblePosition)
                if (laidOut != null) {
                    if (holdsFocusWithin(laidOut)) {
                        if (requested) finish()
                    } else {
                        focusSameKind(focused, laidOut)
                        requested = true
                    }
                }
                if (passes >= ROW_FOCUS_LAYOUT_PASSES) finish()
            }

            private fun finish() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (pendingRowFocus === this) pendingRowFocus = null
            }
        }
        pendingRowFocus = listener
        setSelectionFromTop(target, if (step > 0) height - row.height else 0)
        viewTreeObserver.addOnGlobalLayoutListener(listener)
        return true
    }

    /** Whether the focus is on [row] or on a view inside it. */
    private fun holdsFocusWithin(row: View): Boolean {
        var current: View? = findFocus()
        while (current != null) {
            if (current === row) return true
            current = current.parent as? View
        }
        return false
    }

    /** The row of this list that holds [view], or null when [view] is not inside the list. */
    private fun rowOf(view: View): View? {
        var current: View? = view
        while (current != null) {
            val parent = current.parent as? View
            if (parent === this) return current
            current = parent
        }
        return null
    }

    /**
     * Gives the focus to the view in [row] that is of the same kind as [from]: the channel cell, or else the programme timeline.
     * A remote key leaves touch mode before the view sees it. The keys that the device checks send do not, so the request makes
     * that transition with requestFocusFromTouch(). With a remote the window has already left touch mode, so nothing changes.
     */
    private fun focusSameKind(from: View, row: View): Boolean {
        val group = row as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val sameKind = if (from is TextView) child is TextView else child is ProgramTimelineView
            if (sameKind) return child.requestFocusFromTouch()
        }
        return false
    }

    private companion object {
        const val ROW_FOCUS_LAYOUT_PASSES = 8
    }
}

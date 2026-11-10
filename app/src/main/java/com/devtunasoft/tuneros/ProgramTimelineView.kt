package com.devtunasoft.tuneros

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.TimelineEntry
import com.devtunasoft.tuneros.core.TimelineFocusPolicy

/**
 * One low-allocation timeline surface for a guide row.
 *
 * Program cells are drawn directly instead of materialized as child Views. This removes
 * the old fixed cell cap and keeps View count constant regardless of programme density.
 */
internal class ProgramTimelineView(
    context: Context,
    private val onChannelSelected: (Channel) -> Unit,
    private val onProgramFocused: (Channel, GuideProgram?) -> Unit,
    private val onWindowShift: (Int) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val horizontalPadding = 10f * density
    private val borderWidth = 1f * density

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = Color.rgb(52, 73, 99)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.scaledDensity
    }
    private val textBaselineOffset = run {
        val metrics = textPaint.fontMetrics
        -(metrics.ascent + metrics.descent) / 2f
    }

    private var channel: Channel? = null
    private var entries: List<TimelineEntry> = emptyList()
    private var fromEpochSeconds: Long = 0
    private var toEpochSeconds: Long = 1
    private var selectedIndex: Int = -1

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun bind(
        channel: Channel,
        entries: List<TimelineEntry>,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
        preferredProgramStart: Long?,
    ) {
        require(toEpochSeconds > fromEpochSeconds)
        this.channel = channel
        this.entries = entries
        this.fromEpochSeconds = fromEpochSeconds
        this.toEpochSeconds = toEpochSeconds
        selectedIndex = TimelineFocusPolicy.preferredIndex(entries, preferredProgramStart)

        val selectable = selectedIndex >= 0
        isFocusable = selectable
        isClickable = selectable
        updateAccessibilityDescription()
        invalidate()
        if (hasFocus()) notifyFocusedProgram()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty() || width <= 0 || height <= 0) return

        val duration = (toEpochSeconds - fromEpochSeconds).toDouble()
        val contentWidth = width.toFloat()
        val contentHeight = height.toFloat()

        for (index in entries.indices) {
            val entry = entries[index]
            val left = (((entry.startEpochSeconds - fromEpochSeconds) / duration) * contentWidth)
                .toFloat()
                .coerceIn(0f, contentWidth)
            val right = (((entry.endEpochSeconds - fromEpochSeconds) / duration) * contentWidth)
                .toFloat()
                .coerceIn(left, contentWidth)
            if (right <= left) continue

            fillPaint.color = when {
                hasFocus() && index == selectedIndex -> Color.rgb(31, 103, 190)
                entry.program == null -> Color.rgb(20, 32, 48)
                else -> Color.rgb(28, 48, 72)
            }
            canvas.drawRect(left, 0f, right, contentHeight, fillPaint)
            canvas.drawRect(left, 0f, right, contentHeight, borderPaint)

            val program = entry.program ?: continue
            val textLeft = left + horizontalPadding
            if (right - textLeft <= horizontalPadding) continue
            val save = canvas.save()
            canvas.clipRect(textLeft, 0f, right - horizontalPadding, contentHeight)
            canvas.drawText(
                program.title,
                textLeft,
                contentHeight / 2f + textBaselineOffset,
                textPaint,
            )
            canvas.restoreToCount(save)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> return moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return moveSelection(1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> return performClick()
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveSelection(direction: Int): Boolean {
        val next = TimelineFocusPolicy.nextIndex(entries, selectedIndex, direction)
        if (next >= 0) {
            setSelectedIndex(next)
            return true
        }
        if (direction > 0) {
            onWindowShift(direction)
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isClickable) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                val index = entryIndexAt(event.x)
                if (index >= 0 && entries[index].program != null) {
                    requestFocus()
                    setSelectedIndex(index)
                    performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        val selectedChannel = channel ?: return false
        entries.getOrNull(selectedIndex)?.program ?: return false
        onChannelSelected(selectedChannel)
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) notifyFocusedProgram()
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val program = entries.getOrNull(selectedIndex)?.program
        info.className = "android.widget.Button"
        info.isClickable = program != null
        info.isFocusable = program != null
        info.text = program?.title ?: channel?.name
        if (program != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }

    private fun setSelectedIndex(index: Int) {
        if (index !in entries.indices || entries[index].program == null) return
        if (selectedIndex == index) {
            notifyFocusedProgram()
            return
        }
        selectedIndex = index
        updateAccessibilityDescription()
        notifyFocusedProgram()
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    private fun notifyFocusedProgram() {
        if (!hasFocus()) return
        val selectedChannel = channel ?: return
        onProgramFocused(selectedChannel, entries.getOrNull(selectedIndex)?.program)
    }

    private fun updateAccessibilityDescription() {
        val selectedChannel = channel
        val program = entries.getOrNull(selectedIndex)?.program
        contentDescription = when {
            selectedChannel == null -> null
            program == null -> selectedChannel.name
            else -> selectedChannel.name + ", " + program.title
        }
    }

    private fun entryIndexAt(x: Float): Int {
        if (entries.isEmpty() || width <= 0) return -1
        val clamped = x.coerceIn(0f, width.toFloat())
        val target = fromEpochSeconds +
            ((clamped / width.toFloat()) * (toEpochSeconds - fromEpochSeconds)).toLong()
        for (index in entries.indices) {
            val entry = entries[index]
            if (target >= entry.startEpochSeconds && target < entry.endEpochSeconds) {
                return index
            }
        }
        return entries.lastIndex.takeIf {
            clamped == width.toFloat() &&
                entries[it].endEpochSeconds == toEpochSeconds
        } ?: -1
    }
}

package com.devtunasoft.tuneros

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.TimelineLayout

/**
 * Low-allocation cable guide adapter.
 *
 * ListView owns vertical virtualization. Each recycled row contains only a channel label
 * and one custom timeline surface, so View count is constant even when a three-hour window
 * contains many short programmes.
 */
internal class CableGuideAdapter(
    private val context: Context,
    private val onChannelSelected: (Channel) -> Unit,
    private val onProgramFocused: (Channel, GuideProgram?) -> Unit,
    private val onWindowShift: (Int) -> Unit,
    private val onChannelLongPressed: (Channel) -> Unit,
) : BaseAdapter() {
    companion object {
        private const val CHANNEL_WIDTH_DP = 190
        private const val ROW_HEIGHT_DP = 64
    }

    private class RowHolder(
        val channelView: TextView,
        val timelineView: ProgramTimelineView,
    ) {
        var boundChannel: Channel? = null
    }

    private var channels: List<Channel> = emptyList()
    private var favoriteIds: Set<String> = emptySet()
    private var guide: GuideSnapshot? = null
    private var fromEpochSeconds: Long = 0
    private var toEpochSeconds: Long = 1
    private var nowEpochSeconds: Long = 0
    private var focusedChannelId: String? = null
    private var focusedProgramStart: Long? = null
    private var pendingFocusShiftDirection = 0

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun update(
        visibleChannels: List<Channel>,
        snapshot: GuideSnapshot?,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
        nowEpochSeconds: Long,
        favoriteIds: Set<String>,
    ) {
        require(toEpochSeconds > fromEpochSeconds)
        channels = visibleChannels
        this.favoriteIds = favoriteIds
        guide = snapshot
        this.fromEpochSeconds = fromEpochSeconds
        this.toEpochSeconds = toEpochSeconds
        this.nowEpochSeconds = nowEpochSeconds
        notifyDataSetChanged()
    }

    /**
     * Update only visible now-markers on a minute tick. A full
     * notifyDataSetChanged() would requery/slice EPG programmes and rebuild
     * every visible cable row, even though schedules and window are unchanged.
     * Off-screen rows receive the latest timestamp when ListView recycles them.
     */
    fun refreshClock(list: ListView, now: Long) {
        nowEpochSeconds = now
        if (list.adapter !== this) return
        for (position in 0 until list.childCount) {
            val row = list.getChildAt(position) as? LinearLayout ?: continue
            val holder = row.tag as? RowHolder ?: continue
            holder.timelineView.refreshNow(now)
        }
    }

    /**
     * Boundary keys are recorded against the focus owner, not the View that received them, so a
     * ListView recycle cannot drop the direction before the new window is bound.
     */
    fun requestWindowShift(direction: Int) {
        if (direction != -1 && direction != 1) return
        pendingFocusShiftDirection = direction
        onWindowShift(direction)
    }

    override fun getCount(): Int = channels.size
    override fun getItem(position: Int): Channel = channels[position]
    override fun getItemId(position: Int): Long = channels[position].id.hashCode().toLong()

    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
        val row = recycled as? LinearLayout ?: createRow()
        val holder = row.tag as RowHolder
        bind(holder, channels[position])
        return row
    }

    private fun createRow(): LinearLayout {
        val channelView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            // A white outline on the focused channel cell, so a TV remote user can see which channel has focus.
            foreground = focusRingDrawable(resources.displayMetrics.density)
        }
        val timelineView = ProgramTimelineView(
            context = context,
            onChannelSelected = onChannelSelected,
            onProgramFocused = ::handleProgramFocused,
            onWindowShift = ::requestWindowShift,
        )
        val holder = RowHolder(channelView, timelineView)

        channelView.setOnClickListener {
            holder.boundChannel?.let(onChannelSelected)
        }
        // A long press on the centre key toggles a favorite. The key listener above does not consume it.
        channelView.setOnLongClickListener {
            holder.boundChannel?.let(onChannelLongPressed)
            true
        }
        channelView.setOnFocusChangeListener { _, focused ->
            if (focused) holder.boundChannel?.let { onProgramFocused(it, null) }
        }
        channelView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                requestWindowShift(-1)
                true
            } else {
                false
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(ROW_HEIGHT_DP)
            addView(
                channelView,
                LinearLayout.LayoutParams(dp(CHANNEL_WIDTH_DP), dp(ROW_HEIGHT_DP)),
            )
            addView(
                timelineView,
                LinearLayout.LayoutParams(0, dp(ROW_HEIGHT_DP), 1f),
            )
            tag = holder
        }
    }

    private fun bind(holder: RowHolder, channel: Channel) {
        val viewPreviousChannelId = holder.boundChannel?.id
        holder.boundChannel = channel
        holder.channelView.text = if (channel.storageKey in favoriteIds) "★ " + channel.name else channel.name

        val snapshot = guide
        val guideId = channel.guideId
        val entries = if (snapshot != null && guideId != null) {
            TimelineLayout.rowFromSorted(
                snapshot.programsBetween(guideId, fromEpochSeconds, toEpochSeconds),
                fromEpochSeconds,
                toEpochSeconds,
            )
        } else {
            TimelineLayout.row(emptyList(), fromEpochSeconds, toEpochSeconds)
        }

        val shiftDirection = GuideRowFocusPolicy.shiftDirectionForBind(
            viewPreviousChannelId = viewPreviousChannelId,
            boundChannelId = channel.id,
            focusedChannelId = focusedChannelId,
            pendingDirection = pendingFocusShiftDirection,
        )
        holder.timelineView.bind(
            channel = channel,
            entries = entries,
            fromEpochSeconds = fromEpochSeconds,
            toEpochSeconds = toEpochSeconds,
            nowEpochSeconds = nowEpochSeconds,
            preferredProgramStart = if (channel.id == focusedChannelId) {
                focusedProgramStart
            } else {
                null
            },
            windowShiftDirection = shiftDirection,
        )
        if (shiftDirection != 0) pendingFocusShiftDirection = 0
    }

    private fun handleProgramFocused(channel: Channel, program: GuideProgram?) {
        // A focus move to another channel invalidates any shift recorded for the old owner.
        if (focusedChannelId != channel.id) pendingFocusShiftDirection = 0
        focusedChannelId = channel.id
        focusedProgramStart = program?.startEpochSeconds
        onProgramFocused(channel, program)
    }
}

/**
 * Pure decision for whether a pending D-pad window-shift direction applies to a row being bound.
 * No Android dependency so the recycling rule can be asserted on the JVM with synthetic channels.
 */
internal object GuideRowFocusPolicy {
    /**
     * The direction belongs to the focused channel recorded by the adapter, never to the
     * particular View instance that received the key. [viewPreviousChannelId] is passed by the
     * recycled-row call site for documentation; it is deliberately absent from the decision so a
     * row rebound from another channel still keeps its focus owner's pending shift.
     */
    fun shiftDirectionForBind(
        viewPreviousChannelId: String?,
        boundChannelId: String,
        focusedChannelId: String?,
        pendingDirection: Int,
    ): Int = if (focusedChannelId == boundChannelId && pendingDirection != 0) pendingDirection else 0
}

package com.devtunasoft.tuneros

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
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
    private var guide: GuideSnapshot? = null
    private var fromEpochSeconds: Long = 0
    private var toEpochSeconds: Long = 1
    private var nowEpochSeconds: Long = 0
    private var focusedChannelId: String? = null
    private var focusedProgramStart: Long? = null

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun update(
        visibleChannels: List<Channel>,
        snapshot: GuideSnapshot?,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
        nowEpochSeconds: Long,
    ) {
        require(toEpochSeconds > fromEpochSeconds)
        channels = visibleChannels
        guide = snapshot
        this.fromEpochSeconds = fromEpochSeconds
        this.toEpochSeconds = toEpochSeconds
        this.nowEpochSeconds = nowEpochSeconds
        notifyDataSetChanged()
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
        }
        val timelineView = ProgramTimelineView(
            context = context,
            onChannelSelected = onChannelSelected,
            onProgramFocused = ::handleProgramFocused,
            onWindowShift = onWindowShift,
        )
        val holder = RowHolder(channelView, timelineView)

        channelView.setOnClickListener {
            holder.boundChannel?.let(onChannelSelected)
        }
        channelView.setOnFocusChangeListener { _, focused ->
            if (focused) holder.boundChannel?.let { onProgramFocused(it, null) }
        }
        channelView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                onWindowShift(-1)
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
        holder.boundChannel = channel
        holder.channelView.text = channel.name

        val snapshot = guide
        val guideId = channel.guideId
        val entries = if (snapshot != null && guideId != null) {
            TimelineLayout.row(
                snapshot.programsBetween(guideId, fromEpochSeconds, toEpochSeconds),
                fromEpochSeconds,
                toEpochSeconds,
            )
        } else {
            TimelineLayout.row(emptyList(), fromEpochSeconds, toEpochSeconds)
        }

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
        )
    }

    private fun handleProgramFocused(channel: Channel, program: GuideProgram?) {
        focusedChannelId = channel.id
        focusedProgramStart = program?.startEpochSeconds
        onProgramFocused(channel, program)
    }
}

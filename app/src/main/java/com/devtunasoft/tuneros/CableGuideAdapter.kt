package com.devtunasoft.tuneros

import android.content.Context
import android.graphics.Color
import android.view.Gravity
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
 * ListView owns vertical virtualization. Each recycled row owns a fixed, small pool
 * of TextViews and rebinds them to the current visible time window; no view is
 * created for off-screen channels or the full XMLTV schedule.
 */
internal class CableGuideAdapter(
    private val context: Context,
    private val onChannelSelected: (Channel) -> Unit,
    private val onProgramFocused: (Channel, GuideProgram?) -> Unit,
) : BaseAdapter() {
    companion object {
        private const val MAX_VISIBLE_CELLS = 8
        private const val CHANNEL_WIDTH_DP = 190
        private const val HOUR_WIDTH_DP = 240
        private const val ROW_HEIGHT_DP = 64
    }

    private data class RowHolder(
        val channel: TextView,
        val timeline: LinearLayout,
        val cells: Array<TextView>,
    )

    private var channels: List<Channel> = emptyList()
    private var guide: GuideSnapshot? = null
    private var fromEpochSeconds: Long = 0
    private var toEpochSeconds: Long = 1
    private var focusedChannelId: String? = null
    private var focusedProgramStart: Long? = null

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun update(
        visibleChannels: List<Channel>,
        snapshot: GuideSnapshot?,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
    ) {
        require(toEpochSeconds > fromEpochSeconds)
        channels = visibleChannels
        guide = snapshot
        this.fromEpochSeconds = fromEpochSeconds
        this.toEpochSeconds = toEpochSeconds
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
        val timeline = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cells = Array(MAX_VISIBLE_CELLS) {
            TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                maxLines = 2
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }.also { timeline.addView(it, LinearLayout.LayoutParams(0, dp(ROW_HEIGHT_DP))) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(ROW_HEIGHT_DP)
            addView(channelView, LinearLayout.LayoutParams(dp(CHANNEL_WIDTH_DP), dp(ROW_HEIGHT_DP)))
            addView(timeline, LinearLayout.LayoutParams(0, dp(ROW_HEIGHT_DP), 1f))
            tag = RowHolder(channelView, timeline, cells)
        }
    }

    private fun bind(holder: RowHolder, channel: Channel) {
        holder.channel.text = channel.name
        holder.channel.setOnClickListener { onChannelSelected(channel) }
        holder.channel.setOnFocusChangeListener { _, focused ->
            if (focused) onProgramFocused(channel, null)
        }

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

        for (index in holder.cells.indices) {
            val cell = holder.cells[index]
            val entry = entries.getOrNull(index)
            if (entry == null) {
                cell.visibility = View.GONE
                cell.setOnClickListener(null)
                cell.onFocusChangeListener = null
                continue
            }
            cell.visibility = View.VISIBLE
            val program = entry.program
            cell.text = program?.title ?: "No EPG"
            cell.layoutParams = (cell.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(TimelineLayout.widthUnits(entry, HOUR_WIDTH_DP))
                weight = 0f
            }
            cell.isEnabled = program != null
            cell.isFocusable = program != null
            cell.setOnClickListener(if (program == null) null else View.OnClickListener {
                onChannelSelected(channel)
            })
            cell.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    focusedChannelId = channel.id
                    focusedProgramStart = program?.startEpochSeconds
                    onProgramFocused(channel, program)
                }
            }
            if (program != null && channel.id == focusedChannelId &&
                program.startEpochSeconds == focusedProgramStart && !cell.hasFocus()) {
                cell.post { if (cell.isShown) cell.requestFocus() }
            }
        }
    }
}

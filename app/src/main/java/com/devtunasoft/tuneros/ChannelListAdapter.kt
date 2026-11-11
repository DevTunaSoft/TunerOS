package com.devtunasoft.tuneros

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.GuideProgram

/** Virtualized rows: only on-screen views are created, even for large playlists. */
internal class ChannelListAdapter(private val context: Context) : BaseAdapter() {
    private var channels: List<Channel> = emptyList()
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    private var current: Map<String, GuideProgram> = emptyMap()

    fun update(visible: List<Channel>, now: Map<String, GuideProgram>) {
        channels = visible
        current = now
        notifyDataSetChanged()
    }

    override fun getCount() = channels.size
    override fun getItem(position: Int): Channel = channels[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
        val view = recycled as? TextView ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(20), dp(18), dp(16), dp(18))
            isFocusable = false
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val channel = channels[position]
        val program = channel.guideId?.let(current::get)
        view.text = channel.name + "  •  " + (program?.title ?: "No current EPG")
        return view
    }
}

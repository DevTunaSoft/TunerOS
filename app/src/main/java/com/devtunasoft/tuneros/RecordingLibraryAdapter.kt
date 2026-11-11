package com.devtunasoft.tuneros

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Small recycled list for recent recordings. It never scans storage and keeps only
 * the bounded catalog query supplied by MainActivity.
 */
internal class RecordingLibraryAdapter(
    private val context: Context,
    private val onSelected: (RecordingCatalogEntry) -> Unit,
    private val onFocused: (RecordingCatalogEntry) -> Unit = {},
) : BaseAdapter() {
    private var entries: List<RecordingCatalogEntry> = emptyList()
    private val timeFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")

    fun update(items: List<RecordingCatalogEntry>) {
        entries = items
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): RecordingCatalogEntry = entries[position]
    override fun getItemId(position: Int): Long = entries[position].id.hashCode().toLong()

    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
        val view = recycled as? TextView ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            minHeight = dp(58)
        }
        val entry = entries[position]
        val whenText = entry.startedEpochSeconds?.let {
            timeFormat.format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()))
        } ?: "Unknown time"
        val sizeMiB = entry.bytesWritten / (1024.0 * 1024.0)
        val playable = entry.state == "COMPLETED" && entry.storageLocation != null
        view.text = buildString {
            append(entry.title)
            append("  ·  ")
            append(entry.state)
            if (entry.isProtected) append("  ·  PROTECTED")
            append("\n")
            append(whenText)
            append("  ·  ")
            append(String.format("%.1f MiB", sizeMiB))
            if (!playable) append("  ·  Not playable")
        }
        view.isEnabled = true
        view.isFocusable = true
        view.setOnClickListener { onSelected(entry) }
        view.setOnFocusChangeListener { _, focused ->
            if (focused) onFocused(entry)
        }
        return view
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

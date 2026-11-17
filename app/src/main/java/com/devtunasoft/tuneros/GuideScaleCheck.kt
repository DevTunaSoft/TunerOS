package com.devtunasoft.tuneros

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Debug
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.ChannelVisibility
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import java.io.File

/**
 * Debug-only guide scale measurement for unattended emulator QA. It starts only from the debug launch extra
 * `tuneros.debug.guide_scale_check`, in a launch of its own, so the main self-test pass does not run beside it.
 *
 * The workload matches the stress fixture: 160 channels with 72 programmes each, plus one channel without a guide,
 * generated in memory. Every row is bound through the app's [CableGuideAdapter] with one recycled row, as a ListView
 * recycles, then measured, laid out and drawn into one reusable bitmap. The adapter creates and draws views, so the
 * scenario runs on the UI thread. It uses no preferences, stores, playlists, recordings or network.
 *
 * Process PSS is read from Debug.MemoryInfo before and after the scenario. Wall time and CPU time cover the scenario
 * only, and the scenario includes first-use class loading. The only gate is completion: PASS means the scenario ran to
 * the end, every row showed its own channel name, and the MEASURE lines were written. There are no time or memory
 * budgets, so the numbers carry no verdict.
 */
internal object GuideScaleCheck {
    private const val REPORT_FILE = "tuneros-guide-scale-report.txt"
    private const val CHANNEL_COUNT = 160
    private const val PROGRAMS_PER_CHANNEL = 72
    private const val BASE_EPOCH_SECONDS = 1_800_000_000L
    private const val HALF_HOUR = 1_800L
    private const val WINDOW_SLOTS = 6

    fun run(context: Context) {
        val report = try {
            scenario(context)
        } catch (error: Exception) {
            "FAIL\tguide-scale-complete\tException: " + error.javaClass.simpleName + "\n"
        }
        File(context.filesDir, REPORT_FILE).writeText(report)
    }

    private fun scenario(context: Context): String {
        val pssBefore = totalPssKiB()
        val startMs = SystemClock.elapsedRealtime()
        val startCpuMs = SystemClock.currentThreadTimeMillis()

        val channels = ArrayList<Channel>(CHANNEL_COUNT + 1)
        val programmes = ArrayList<GuideProgram>(CHANNEL_COUNT * PROGRAMS_PER_CHANNEL)
        for (channel in 0 until CHANNEL_COUNT) {
            val id = "qa-guide-$channel"
            channels.add(Channel(
                id = id,
                name = "QA Channel $channel",
                streamUrl = "https://example.invalid/qa/$channel",
                guideId = id,
            ))
            for (slot in 0 until PROGRAMS_PER_CHANNEL) {
                val from = BASE_EPOCH_SECONDS + slot * HALF_HOUR
                programmes.add(GuideProgram(
                    channelId = id,
                    title = "QA Show $slot",
                    startEpochSeconds = from,
                    endEpochSeconds = from + HALF_HOUR,
                ))
            }
        }
        channels.add(Channel(
            id = "qa-without-epg",
            name = "No EPG",
            streamUrl = "https://example.invalid/qa/no-guide",
        ))
        val guide = GuideSnapshot(programmes, emptyMap())
        guide.prepareIndex()
        val windowStart = BASE_EPOCH_SECONDS + 20 * HALF_HOUR
        val windowEnd = windowStart + WINDOW_SLOTS * HALF_HOUR
        // Hide-without-guide is off, so the channel without a guide is rendered too.
        val visible = ChannelVisibility.filter(channels, guide, "", false)

        val adapter = CableGuideAdapter(
            context,
            onChannelSelected = { },
            onProgramFocused = { _, _ -> },
            onWindowShift = { },
            onChannelLongPressed = { },
        )
        adapter.update(visible, guide, windowStart, windowEnd, windowStart + 1, emptySet())
        val ownNameRows = renderRows(context, adapter, visible)

        // Timing ends before the closing PSS read, so the read itself is not counted.
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        val cpuMs = SystemClock.currentThreadTimeMillis() - startCpuMs
        val pssAfter = totalPssKiB()
        val complete = ownNameRows == visible.size
        return listOf(
            "MEASURE\tguide-scale-channels\t" + channels.count { it.guideId != null },
            "MEASURE\tguide-scale-programmes\t" + programmes.size,
            "MEASURE\tguide-scale-rows-rendered\t" + visible.size,
            "MEASURE\tguide-scale-elapsed-ms\t" + elapsedMs,
            "MEASURE\tguide-scale-cpu-ms\t" + cpuMs,
            "MEASURE\tguide-scale-pss-before-kib\t" + pssBefore,
            "MEASURE\tguide-scale-pss-after-kib\t" + pssAfter,
            "MEASURE\tguide-scale-pss-delta-kib\t" + (pssAfter - pssBefore),
            if (complete) {
                "PASS\tguide-scale-complete\tscenario completed and measurements recorded"
            } else {
                "FAIL\tguide-scale-complete\trows did not show their own channel names"
            },
        ).joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * Binds each row through the adapter with one recycled row, then measures, lays out and draws it. Returns how many
     * rows showed their own channel name. The bitmap is one reusable surface, released when the rows are done.
     */
    private fun renderRows(context: Context, adapter: CableGuideAdapter, visible: List<Channel>): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            context.resources.displayMetrics.widthPixels,
            View.MeasureSpec.EXACTLY,
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val container = FrameLayout(context)
        var surface: Bitmap? = null
        var recycled: View? = null
        var ownNames = 0
        try {
            for (position in visible.indices) {
                val row = adapter.getView(position, recycled, container)
                row.measure(widthSpec, heightSpec)
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                val target = surface ?: Bitmap.createBitmap(
                    row.measuredWidth,
                    row.measuredHeight,
                    Bitmap.Config.ARGB_8888,
                ).also { surface = it }
                row.draw(Canvas(target))
                // The adapter's row holds the channel label first, then the timeline.
                val label = ((row as? ViewGroup)?.getChildAt(0) as? TextView)?.text?.toString()
                if (label == visible[position].name) ownNames++
                recycled = row
            }
        } finally {
            surface?.recycle()
        }
        return ownNames
    }

    private fun totalPssKiB(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }
}

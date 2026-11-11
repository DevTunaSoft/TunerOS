package com.devtunasoft.tuneros

import android.os.Debug
import android.os.SystemClock
import com.devtunasoft.tuneros.core.Channel
import com.devtunasoft.tuneros.core.ChannelVisibility
import com.devtunasoft.tuneros.core.GuideProgram
import com.devtunasoft.tuneros.core.GuideSnapshot
import com.devtunasoft.tuneros.core.TimelineLayout

/**
 * Bounded guide workload for unattended debug/emulator QA, not normal app startup.
 *
 * It uses synthetic URLs only (no network), one contiguous generated fixture and
 * no persistent channel data. It cannot modify actual playlists, EPG or recordings.
 */
internal object OnDeviceGuideStress {
    private const val CHANNEL_COUNT = 160
    private const val PROGRAMS_PER_CHANNEL = 72
    private const val BASE_EPOCH_SECONDS = 1_800_000_000L
    private const val HALF_HOUR = 1_800L
    private const val MAX_ELAPSED_MS = 10_000L
    private const val MAX_HEAP_GROWTH_BYTES = 32L * 1024 * 1024

    fun run(): OnDeviceSelfTest.Check {
        val startMs = SystemClock.elapsedRealtime()
        val startCpuMs = SystemClock.currentThreadTimeMillis()
        val pssBefore = Debug.getPss()
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        return try {
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
            val windowEnd = windowStart + 6 * HALF_HOUR
            var valid = true
            for (index in 0 until CHANNEL_COUNT) {
                val id = "qa-guide-$index"
                val active = guide.now(id, windowStart + 1)
                val window = guide.programsBetween(id, windowStart, windowEnd)
                val timeline = TimelineLayout.rowFromSorted(window, windowStart, windowEnd)
                valid = valid &&
                    active?.title == "QA Show 20" &&
                    window.size == 6 &&
                    timeline.size == 6 &&
                    timeline.first().startEpochSeconds == windowStart &&
                    timeline.last().endEpochSeconds == windowEnd
            }
            val filtered = ChannelVisibility.filter(channels, guide, "", true)
            val searched = ChannelVisibility.filter(channels, guide, "QA Channel 15", true)
            valid = valid &&
                filtered.size == CHANNEL_COUNT &&
                searched.size == 11 && // Channel 15 and channels 150..159.
                guide.now("qa-guide-0", BASE_EPOCH_SECONDS - 1) == null &&
                guide.now("qa-guide-0", BASE_EPOCH_SECONDS + PROGRAMS_PER_CHANNEL * HALF_HOUR) == null

            val duration = SystemClock.elapsedRealtime() - startMs
            val cpuMs = SystemClock.currentThreadTimeMillis() - startCpuMs
            val endPss = Debug.getPss()
            val heapGrowth = (runtime.totalMemory() - runtime.freeMemory() - heapBefore)
            val details = "channels=$CHANNEL_COUNT programs=${programmes.size} " +
                "elapsedMs=$duration cpuMs=$cpuMs " +
                "pssBeforeKiB=$pssBefore pssAfterKiB=$endPss heapGrowthBytes=$heapGrowth"
            OnDeviceSelfTest.Check(
                "debug-large-guide-stress",
                if (valid && duration <= MAX_ELAPSED_MS && heapGrowth <= MAX_HEAP_GROWTH_BYTES)
                    OnDeviceSelfTest.State.PASS else OnDeviceSelfTest.State.FAIL,
                details,
            )
        } catch (error: Exception) {
            OnDeviceSelfTest.Check(
                "debug-large-guide-stress",
                OnDeviceSelfTest.State.FAIL,
                "Exception: " + error.javaClass.simpleName,
            )
        }
    }
}
